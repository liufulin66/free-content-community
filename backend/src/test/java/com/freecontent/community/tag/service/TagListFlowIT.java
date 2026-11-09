package com.freecontent.community.tag.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.tag.ratelimit.TagQueryRateLimiter;
import com.freecontent.community.tag.entity.TagEntity;
import com.freecontent.community.tag.mapper.TagMapper;
import com.freecontent.community.tag.model.TagStatus;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class TagListFlowIT {
    private static final String PREFIX = "test:tag-list:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @MockitoSpyBean private TagMapper tags;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private TagQueryRateLimiter limiter;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sqlSession;
    private final List<String> actors = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.query.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.query.user-per-minute-limit", () -> 6);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String actor : actors) {
            jdbc.update("DELETE FROM tag_creation_request WHERE created_by=?", actor);
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", actor);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", actor);
            users.deleteById(actor);
        }
    }

    @Test
    void administratorReceivesTheDefaultPageWithOnlyContractFields() throws Exception {
        UserAccount admin = actor("ADMIN");
        tag(admin, TagStatus.ACTIVE);
        tag(actor("ADMIN"), TagStatus.DISABLED);
        long total = count();

        HttpResponse<String> response = get("", token(admin));

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("items", "page", "pageSize", "total"), data.keySet());
        assertEquals(1, ((Number) data.get("page")).intValue());
        assertEquals(20, ((Number) data.get("pageSize")).intValue());
        assertEquals(total, ((Number) data.get("total")).longValue());
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertEquals(Math.min(total, 20), items.size());
        for (Map<String, Object> item : items) {
            assertEquals(Set.of("id", "name", "description", "status", "version"), item.keySet());
        }
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertNotNull(Instant.parse(JsonPath.read(response.body(), "$.serverTime")));
    }

    @ParameterizedTest
    @CsvSource({
            "?page=abc, 400, page", "?page=1.2, 400, page", "?page=, 400, page", "?page=null, 400, page",
            "?pageSize=true, 400, pageSize", "?pageSize=1e2, 400, pageSize", "?pageSize=, 400, pageSize",
            "?page=0, 422, page", "?page=-1, 422, page", "?pageSize=0, 422, pageSize",
            "?pageSize=101, 422, pageSize", "?pageSize=9999999999999999999999, 422, pageSize"
    })
    void invalidPaginationHasTheContractStatusAndQueryViolation(String query, int status, String field) throws Exception {
        HttpResponse<String> response = get(query, token(actor("ADMIN")));

        assertEquals(status, response.statusCode(), response.body());
        assertEquals(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR", JsonPath.read(response.body(), "$.code"));
        assertEquals("query", JsonPath.read(response.body(), "$.violations[0].location"));
        assertEquals(field, JsonPath.read(response.body(), "$.violations[0].path"));
        assertTrue(redis.keys(PREFIX + "{tag-query}:*").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?sort=name", "?status=ACTIVE", "?page=1&page=2", "?pageSize=20&pageSize=20"})
    void unknownAndRepeatedQueryParametersAreRejected(String query) throws Exception {
        HttpResponse<String> response = get(query, token(actor("ADMIN")));
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("MALFORMED_REQUEST", JsonPath.read(response.body(), "$.code"));
        assertTrue(redis.keys(PREFIX + "{tag-query}:*").isEmpty());
    }

    @Test
    void pagesIncludeBothStatusesAndOtherAdministratorsTagsInStableOrder() throws Exception {
        UserAccount reader = actor("ADMIN"), other = actor("ADMIN");
        LocalDateTime latest = jdbc.queryForObject("SELECT MAX(created_at) FROM community_tag", LocalDateTime.class);
        LocalDateTime stamp = (latest == null ? LocalDateTime.of(2026, 1, 1, 0, 0) : latest).plusSeconds(2);
        TagEntity first = tag(reader, TagStatus.ACTIVE), second = tag(other, TagStatus.DISABLED), third = tag(reader, TagStatus.DISABLED);
        for (TagEntity item : List.of(first, second)) jdbc.update("UPDATE community_tag SET created_at=? WHERE id=?", stamp, item.getId());
        jdbc.update("UPDATE community_tag SET created_at=? WHERE id=?", stamp.minusSeconds(1), third.getId());
        List<String> expected = new ArrayList<>(List.of(first.getId(), second.getId()));
        expected.sort(java.util.Comparator.reverseOrder());
        expected.add(third.getId());
        var before = jdbc.queryForList("SELECT * FROM community_tag WHERE created_by IN (?,?) ORDER BY id", reader.getId(), other.getId());
        String token = token(reader);
        long total = count();

        for (int page = 1; page <= 3; page++) {
            var response = get("?page=" + page + "&pageSize=1", token);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(expected.get(page - 1), JsonPath.read(response.body(), "$.data.items[0].id"));
            assertEquals(total, ((Number) JsonPath.read(response.body(), "$.data.total")).longValue());
        }
        var beyond = get("?page=" + (total + 1) + "&pageSize=1", token);
        assertEquals(200, beyond.statusCode(), beyond.body());
        assertTrue(JsonPath.<List<?>>read(beyond.body(), "$.data.items").isEmpty());
        assertEquals(total, ((Number) JsonPath.read(beyond.body(), "$.data.total")).longValue());
        assertEquals(before, jdbc.queryForList("SELECT * FROM community_tag WHERE created_by IN (?,?) ORDER BY id", reader.getId(), other.getId()));
    }

    @Test
    void maximumPageSizeAndArbitrarilyLargePageNumbersFollowTheContract() throws Exception {
        UserAccount admin = actor("ADMIN");
        tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        var maximum = get("?pageSize=100", token);
        assertEquals(200, maximum.statusCode(), maximum.body());
        assertEquals(100, ((Number) JsonPath.read(maximum.body(), "$.data.pageSize")).intValue());
        clearInvocations(tags);
        BigInteger huge = new BigInteger("9".repeat(80));

        var beyond = get("?page=" + huge, token);

        assertEquals(200, beyond.statusCode(), beyond.body());
        assertEquals(huge, new BigInteger(JsonPath.read(beyond.body(), "$.data.page").toString()));
        assertTrue(JsonPath.<List<?>>read(beyond.body(), "$.data.items").isEmpty());
        assertEquals(count(), ((Number) JsonPath.read(beyond.body(), "$.data.total")).longValue());
        verify(tags, never()).selectPage(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "DETAIL", "EMPTY_LIST"})
    void listAndDetailShareTheirMinuteQuotaIncludingEmptyPages(String firstRequest) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        for (int i = 0; i < 5; i++) limiter.acquire(admin.getId(), "seed-source-" + i);
        var first = "DETAIL".equals(firstRequest)
                ? request("/api/v1/admin/tags/" + tag.getId(), token)
                : get("EMPTY_LIST".equals(firstRequest) ? "?page=999999999" : "", token);
        assertEquals(200, first.statusCode(), first.body());

        var limited = "DETAIL".equals(firstRequest) ? get("", token)
                : request("/api/v1/admin/tags/" + tag.getId(), token);

        assertProblem(limited, 429, "RATE_LIMITED");
        long retry = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retry >= 1 && retry <= 60);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "MODERATOR"})
    void nonAdministratorsCannotReadTagListsOrConsumeTheQueryQuota(String role) throws Exception {
        clearInvocations(tags);
        assertProblem(get("", token(actor(role))), 403, "PERMISSION_DENIED");
        verifyNoInteractions(tags);
        assertTrue(redis.keys(PREFIX + "{tag-query}:*").isEmpty());
    }

    @Test
    void missingAndMalformedCredentialsRequireBearerAuthentication() throws Exception {
        clearInvocations(tags);
        for (String token : java.util.Arrays.asList(null, "bad-token")) {
            var response = get("", token);
            assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
        verifyNoInteractions(tags);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVOKED_ADMIN", "CHANGED_AUTH_VERSION", "DISABLED", "UNVERIFIED", "WRITE_BLOCKED"})
    void accountAndRoleChangesAfterAuthenticationAreRecheckedBeforeCountingTags(String change) throws Exception {
        UserAccount admin = actor("ADMIN");
        tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        clearInvocations(tags);
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            switch (change) {
                case "REVOKED_ADMIN" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='ADMIN'", admin.getId());
                case "CHANGED_AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", admin.getId());
                case "DISABLED" -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", admin.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", admin.getId());
                case "WRITE_BLOCKED" -> jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", admin.getId());
            }
            return principal;
        }).when(authentication).authenticate(token);

        var response = get("", token);

        switch (change) {
            case "WRITE_BLOCKED" -> assertEquals(200, response.statusCode(), response.body());
            case "CHANGED_AUTH_VERSION" -> assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
            case "DISABLED" -> assertProblem(response, 403, "ACCOUNT_RESTRICTED");
            default -> assertProblem(response, 403, "PERMISSION_DENIED");
        }
        if (!"WRITE_BLOCKED".equals(change)) verifyNoInteractions(tags);
    }

    @Test
    void writeBlockedAdministratorCanReadBeforeAuthentication() throws Exception {
        UserAccount admin = actor("ADMIN");
        String token = token(admin);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", admin.getId());
        assertEquals(200, get("", token).statusCode());
    }

    @Test
    void databaseCountFailureReturns503WithoutInternalDetails() throws Exception {
        UserAccount admin = actor("ADMIN");
        doThrow(new TransientDataAccessResourceException("sensitive database details")).when(tags).selectCount(isNull());

        var response = get("", token(admin));

        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive database details"));
    }

    @Test
    void redisFailureReturns503BeforeCountingTags() throws Exception {
        UserAccount admin = actor("ADMIN");
        String token = token(admin);
        clearInvocations(tags);
        doThrow(new RedisConnectionFailureException("sensitive redis details")).when(redis)
                .execute(any(RedisScript.class), argThat((List<String> keys) -> !keys.isEmpty()
                        && keys.get(0).startsWith(PREFIX + "{tag-query}:")), any(), any());

        var response = get("", token);

        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive redis details"));
        verifyNoInteractions(tags);
    }

    @Test
    void countAndItemsUseOneSnapshotWhenAnotherTagCommitsBetweenQueries() throws Exception {
        UserAccount reader = actor("ADMIN"), writer = actor("ADMIN");
        tag(reader, TagStatus.ACTIVE);
        long before = count();
        LocalDateTime stamp = jdbc.queryForObject("SELECT MAX(created_at) FROM community_tag", LocalDateTime.class).plusSeconds(1);
        AtomicReference<TagEntity> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            Long total = sqlSession.selectOne(TagMapper.class.getName() + ".selectCount",
                    java.util.Collections.singletonMap("ew", invocation.getArgument(0)));
            CompletableFuture.runAsync(() -> {
                TagEntity added = tag(writer, TagStatus.ACTIVE);
                jdbc.update("UPDATE community_tag SET created_at=? WHERE id=?", stamp, added.getId());
                inserted.set(added);
            }).get(10, TimeUnit.SECONDS);
            return total;
        }).when(tags).selectCount(isNull());

        var response = get("", token(reader));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(before, ((Number) JsonPath.read(response.body(), "$.data.total")).longValue());
        List<String> ids = JsonPath.read(response.body(), "$.data.items[*].id");
        assertFalse(ids.contains(inserted.get().getId()));
        assertEquals(Math.min(before, 20), ids.size());
        assertEquals(before + 1, count());
    }

    @Test
    void openApiDeclaresIntegerPaginationAndTheTagPageContract() throws Exception {
        var response = request("/v3/api-docs", null);
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/admin/tags'].get");
        assertEquals("listAdminTags", operation.get("operationId"));
        assertFalse(operation.containsKey("requestBody"));
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) operation.get("parameters");
        assertEquals(2, parameters.size());
        for (var parameter : parameters) {
            assertEquals("query", parameter.get("in"));
            assertEquals(false, parameter.get("required"));
            Map<?, ?> schema = (Map<?, ?>) parameter.get("schema");
            assertEquals("integer", schema.get("type"), schema.toString());
            assertEquals(1, ((Number) schema.get("minimum")).intValue());
            assertInstanceOf(Number.class, schema.get("default"));
            if ("page".equals(parameter.get("name"))) {
                assertEquals(1, ((Number) schema.get("default")).intValue());
                assertFalse(schema.containsKey("maximum"));
            } else {
                assertEquals("pageSize", parameter.get("name"));
                assertEquals(20, ((Number) schema.get("default")).intValue());
                assertEquals(100, ((Number) schema.get("maximum")).intValue());
            }
        }
        Map<?, ?> responses = (Map<?, ?>) operation.get("responses");
        assertEquals(Set.of("200", "400", "401", "403", "422", "429", "500", "503"), responses.keySet());
        String wrapper = JsonPath.read(response.body(),
                "$.paths['/api/v1/admin/tags'].get.responses['200'].content['application/json'].schema['$ref']");
        assertEquals("#/components/schemas/TagPage", JsonPath.read(response.body(),
                "$.components.schemas['" + wrapper.substring(wrapper.lastIndexOf('/') + 1) + "'].properties.data['$ref']"));
        assertEquals(Set.of("items", "page", "pageSize", "total"),
                new java.util.HashSet<>(JsonPath.<List<String>>read(response.body(), "$.components.schemas.TagPage.required")));
        assertEquals("#/components/schemas/Tag", JsonPath.read(response.body(), "$.components.schemas.TagPage.properties.items.items['$ref']"));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    private UserAccount actor(String role) {
        UserAccount user = LoginTestSupport.account();
        user.setId(id());
        user.setAuthVersion(1L);
        user.setEmail("tag-list-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        actors.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        if (!"USER".equals(role)) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,?)",
                user.getId(), role, user.getCreatedAt());
        return user;
    }

    private TagEntity tag(UserAccount creator, TagStatus status) {
        var tag = new TagEntity();
        tag.setId("tag_" + id());
        tag.setName("List_" + id().substring(0, 20));
        tag.setDescription("标签列表测试");
        tag.setStatus(status);
        tag.setVersion(1);
        tag.setCreatedBy(creator.getId());
        tag.setCreatedAt(creator.getCreatedAt());
        tag.setUpdatedAt(creator.getCreatedAt());
        tags.insert(tag);
        return tag;
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "tag-list-test").accessToken();
    }

    private HttpResponse<String> get(String query, String token) throws Exception {
        return request("/api/v1/admin/tags" + query, token);
    }

    private HttpResponse<String> request(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM community_tag", Long.class); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
