package com.freecontent.community.tag.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.freecontent.community.tag.entity.TagEntity;
import com.freecontent.community.tag.mapper.TagMapper;
import com.freecontent.community.tag.model.TagStatus;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ActiveTagFlowIT {
    private static final String PREFIX = "test:active-tags:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @MockitoSpyBean private TagMapper tags;
    @Autowired private UserAccountMapper users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.tag.active-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.active-query.ip-per-minute-limit", () -> 3);
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.update.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test
    void visitorReceivesOnlyActiveTagsInTheContractEnvelope() throws Exception {
        UserAccount owner = owner();
        TagEntity active = tag(owner, TagStatus.ACTIVE), disabled = tag(owner, TagStatus.DISABLED);

        var response = get("", null);

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("items"), data.keySet());
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertTrue(items.stream().anyMatch(item -> active.getId().equals(item.get("id"))));
        assertFalse(items.stream().anyMatch(item -> disabled.getId().equals(item.get("id"))));
        for (Map<String, Object> item : items) {
            assertEquals("ACTIVE", item.get("status"));
            assertEquals(Set.of("id", "name", "description", "status", "version"), item.keySet());
        }
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer stale-token", "Basic abc", "Bearer aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void publicReadsIgnoreCredentialsAndNeverCallTheAuthenticationService(String header) throws Exception {
        var response = get("", header);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
        verifyNoInteractions(authentication);
    }

    @Test
    void repeatedVisitorRequestsConsumeOneSourceMinuteQuota() throws Exception {
        assertEquals(200, get("", null).statusCode());
        assertEquals(200, get("", "Bearer stale-token").statusCode());
        assertEquals(200, get("", "Basic abc").statusCode());

        var limited = get("", null);

        assertEquals(429, limited.statusCode(), limited.body());
        assertEquals("RATE_LIMITED", JsonPath.read(limited.body(), "$.code"));
        long retry = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retry >= 1 && retry <= 60);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=1", "?status=DISABLED", "?name=Java", "?sort=id"})
    void undeclaredQueryParametersReturn400AndStillConsumeTheSourceQuota(String query) throws Exception {
        clearInvocations(tags);

        var response = get(query, null);

        assertProblem(response, 400, "MALFORMED_REQUEST");
        verifyNoInteractions(tags);
        Set<String> counters = redis.keys(PREFIX + "{tag-active-query}:*");
        assertEquals(1, counters.size());
        assertEquals("1", redis.opsForValue().get(counters.iterator().next()));
    }

    @Test
    void databaseFailureIs503WithoutInternalDetails() throws Exception {
        doThrow(new TransientDataAccessResourceException("sensitive database details")).when(tags).selectList(any(Wrapper.class));

        var response = get("", null);

        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive database details"));
    }

    @Test
    void theCompleteActiveListIsSortedByNameAndDoesNotModifyStoredTags() throws Exception {
        UserAccount owner = owner();
        java.util.SortedMap<String, String> expected = new java.util.TreeMap<>();
        String prefix = "Sort_" + id().substring(0, 6) + "_";
        for (int i = 24; i >= 0; i--) {
            TagEntity tag = tag(owner, TagStatus.ACTIVE);
            String name = prefix + String.format(java.util.Locale.ROOT, "%03d", i);
            jdbc.update("UPDATE community_tag SET name=? WHERE id=?", name, tag.getId());
            expected.put(name, tag.getId());
        }
        var before = jdbc.queryForList("SELECT * FROM community_tag WHERE created_by=? ORDER BY id", owner.getId());

        var response = get("", null);

        assertEquals(200, response.statusCode(), response.body());
        List<Map<String, Object>> items = JsonPath.read(response.body(), "$.data.items");
        assertTrue(items.size() >= 25, "公开标签不分页，不应截断到20条");
        var actual = items.stream().map(item -> (String) item.get("id")).filter(expected.values()::contains).toList();
        assertEquals(new ArrayList<>(expected.values()), actual);
        assertEquals(before, jdbc.queryForList("SELECT * FROM community_tag WHERE created_by=? ORDER BY id", owner.getId()));
    }

    @Test
    void noActiveRecordsProduceAnEmptyArray() throws Exception {
        doReturn(List.of()).when(tags).selectList(any(Wrapper.class));
        var response = get("", null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of(), JsonPath.read(response.body(), "$.data.items"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRITE_BLOCKED", "DISABLED", "UNVERIFIED"})
    void credentialsOfRestrictedAccountsCannotBlockThePublicList(String restriction) throws Exception {
        UserAccount owner = owner();
        TagEntity tag = tag(owner, TagStatus.ACTIVE);
        String token = sessions.create(owner.getId(), owner.getAuthVersion(), "public-read-test").accessToken();
        if ("UNVERIFIED".equals(restriction)) jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", owner.getId());
        else jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", restriction, owner.getId());

        var response = get("", "Bearer " + token);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(JsonPath.<List<String>>read(response.body(), "$.data.items[*].id").contains(tag.getId()));
        verifyNoInteractions(authentication);
    }

    @Test
    void committedDisableAndReenableChangesAreVisibleWithoutAListCache() throws Exception {
        UserAccount admin = owner();
        jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,'ADMIN',?)", admin.getId(), admin.getCreatedAt());
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = sessions.create(admin.getId(), admin.getAuthVersion(), "public-read-admin").accessToken();
        assertTrue(JsonPath.<List<String>>read(get("", null).body(), "$.data.items[*].id").contains(tag.getId()));
        for (int version = 1; version <= 2; version++) {
            String status = version == 1 ? "DISABLED" : "ACTIVE";
            var patch = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/tags/" + tag.getId()))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":" + version + ",\"status\":\"" + status + "\"}")).build();
            var changed = client.send(patch, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, changed.statusCode(), changed.body());
            var read = get("", null);
            assertEquals(200, read.statusCode(), read.body());
            assertEquals(version == 2, JsonPath.<List<String>>read(read.body(), "$.data.items[*].id").contains(tag.getId()));
        }
    }

    @Test
    void publicGetDoesNotExposeAdminRoutesOrAllowOtherMethodsAnonymously() throws Exception {
        assertEquals(200, get("", "Bearer ignored").statusCode());
        for (String path : List.of("/api/v1/admin/tags", "/api/v1/me", "/api/v1/tags/private")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), response.body());
        }
        var post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tags"))
                .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(401, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void redisQuotaFailureReturns503BeforeReadingTags() throws Exception {
        clearInvocations(tags);
        doThrow(new RedisConnectionFailureException("sensitive redis details")).when(redis)
                .execute(any(RedisScript.class), argThat((List<String> keys) -> !keys.isEmpty()
                        && keys.get(0).startsWith(PREFIX + "{tag-active-query}:")), any());
        var response = get("", null);
        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive redis details"));
        verifyNoInteractions(tags);
    }

    @Test
    void openApiDeclaresPublicAccessAndTheNonPaginatedTagListSchema() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/tags'].get");
        assertEquals("listActiveTags", operation.get("operationId"));
        assertTrue(operation.get("security") == null || ((List<?>) operation.get("security")).isEmpty());
        assertTrue(operation.get("parameters") == null || ((List<?>) operation.get("parameters")).isEmpty());
        assertFalse(operation.containsKey("requestBody"));
        assertEquals(Set.of("200", "400", "422", "429", "500", "503"), ((Map<?, ?>) operation.get("responses")).keySet());
        String wrapper = JsonPath.read(response.body(), "$.paths['/api/v1/tags'].get.responses['200'].content['application/json'].schema['$ref']");
        assertEquals("#/components/schemas/TagList", JsonPath.read(response.body(),
                "$.components.schemas['" + wrapper.substring(wrapper.lastIndexOf('/') + 1) + "'].properties.data['$ref']"));
        assertEquals(List.of("items"), JsonPath.read(response.body(), "$.components.schemas.TagList.required"));
        Map<?, ?> properties = JsonPath.read(response.body(), "$.components.schemas.TagList.properties");
        assertEquals(Set.of("items"), properties.keySet());
        assertEquals("#/components/schemas/Tag", JsonPath.read(response.body(), "$.components.schemas.TagList.properties.items.items['$ref']"));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
    }

    private UserAccount owner() {
        UserAccount user = LoginTestSupport.account();
        user.setId(id());
        user.setAuthVersion(1L);
        user.setEmail("active-tags-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        owners.add(user.getId());
        users.insert(user);
        return user;
    }

    private TagEntity tag(UserAccount owner, TagStatus status) {
        var tag = new TagEntity();
        tag.setId("tag_" + id());
        tag.setName("Public_" + id().substring(0, 20));
        tag.setDescription("Public tag description");
        tag.setStatus(status);
        tag.setVersion(1);
        tag.setCreatedBy(owner.getId());
        tag.setCreatedAt(owner.getCreatedAt());
        tag.setUpdatedAt(owner.getCreatedAt());
        tags.insert(tag);
        return tag;
    }

    private HttpResponse<String> get(String query, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tags" + query))
                .timeout(Duration.ofSeconds(15)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
