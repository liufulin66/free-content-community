package com.freecontent.community.tag.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class TagQueryFlowIT {
    private static final String PREFIX = "test:tag-query:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @MockitoSpyBean private TagMapper tags;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> actors = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.query.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.query.user-per-minute-limit", () -> 3);
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
    void administratorReadsCurrentTagWithoutChangingItsVersion() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());

        HttpResponse<String> response = get(tag.getId(), token(admin));

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "name", "description", "status", "version"), data.keySet());
        assertEquals(tag.getId(), data.get("id"));
        assertEquals(tag.getName(), data.get("name"));
        assertEquals(tag.getDescription(), data.get("description"));
        assertEquals("ACTIVE", data.get("status"));
        assertEquals(1, data.get("version"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertNotNull(Instant.parse(JsonPath.read(response.body(), "$.serverTime")));
        assertEquals(before, row(tag.getId()));
    }

    @Test
    void unknownAndWrongCaseIdsReturn404RatherThanAnEmptyOrInternalErrorResponse() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);

        assertProblem(get("missing_" + id(), token), 404, "RESOURCE_NOT_FOUND");
        assertProblem(get(tag.getId().toUpperCase(java.util.Locale.ROOT), token), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, get(tag.getId(), token).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVOKED_ADMIN", "CHANGED_AUTH_VERSION", "DISABLED", "UNVERIFIED", "WRITE_BLOCKED"})
    void currentAccountAndRoleAreRecheckedAfterBearerAuthentication(String change) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        Map<String, Object> before = row(tag.getId());
        clearInvocations(tags);
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            changeAccount(admin, change);
            return principal;
        }).when(authentication).authenticate(token);

        HttpResponse<String> response = get(tag.getId(), token);

        switch (change) {
            case "WRITE_BLOCKED" -> assertEquals(200, response.statusCode(), response.body());
            case "CHANGED_AUTH_VERSION" -> assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
            case "DISABLED" -> assertProblem(response, 403, "ACCOUNT_RESTRICTED");
            default -> assertProblem(response, 403, "PERMISSION_DENIED");
        }
        if (!"WRITE_BLOCKED".equals(change)) verifyNoInteractions(tags);
        assertEquals(before, row(tag.getId()));
    }

    @ParameterizedTest
    @MethodSource("invalidTagIds")
    void invalidTagIdsReturnAPathViolationBeforeQueryingTags(String tagId) throws Exception {
        UserAccount admin = actor("ADMIN");
        clearInvocations(tags);

        HttpResponse<String> response = get(tagId, token(admin));

        assertProblem(response, 422, "VALIDATION_ERROR");
        assertEquals("path", JsonPath.read(response.body(), "$.violations[0].location"));
        assertEquals("tagId", JsonPath.read(response.body(), "$.violations[0].path"));
        verifyNoInteractions(tags);
    }

    static Stream<String> invalidTagIds() { return Stream.of("bad.tag", "a".repeat(65)); }

    @Test
    void repeatedQueriesAndNotFoundResultsConsumeTheIndependentMinuteQuota() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);

        assertEquals(200, get(tag.getId(), token).statusCode());
        assertProblem(get("missing_" + id(), token), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, get(tag.getId(), token).statusCode());
        HttpResponse<String> limited = get(tag.getId(), token);

        assertProblem(limited, 429, "RATE_LIMITED");
        long retry = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retry >= 1 && retry <= 60);
        assertEquals(1, row(tag.getId()).get("version"));
    }

    @Test
    void databaseFailureReturns503WithoutLeakingInternalDetails() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());
        doThrow(new TransientDataAccessResourceException("sensitive database details"))
                .when(tags).selectById(tag.getId());

        HttpResponse<String> response = get(tag.getId(), token(admin));

        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive database details"));
        assertEquals(before, row(tag.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DISABLED"})
    void administratorReadsAnotherAdministratorsTagWithCurrentStatusAndVersion(String status) throws Exception {
        UserAccount creator = actor("ADMIN"), reader = actor("ADMIN");
        TagEntity tag = tag(creator, TagStatus.valueOf(status));
        String currentName = "Current_" + id().substring(0, 16);
        jdbc.update("UPDATE community_tag SET name=?,description='',version=7 WHERE id=?", currentName, tag.getId());
        Map<String, Object> before = row(tag.getId());

        HttpResponse<String> response = get(tag.getId(), token(reader));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(currentName, JsonPath.read(response.body(), "$.data.name"));
        assertEquals("", JsonPath.read(response.body(), "$.data.description"));
        assertEquals(status, JsonPath.read(response.body(), "$.data.status"));
        assertEquals(7, (Integer) JsonPath.read(response.body(), "$.data.version"));
        assertEquals(before, row(tag.getId()));
    }

    @Test
    void writeBlockedAdministratorCanReadADisabledTag() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.DISABLED);
        String token = token(admin);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", admin.getId());

        HttpResponse<String> response = get(tag.getId(), token);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("DISABLED", JsonPath.read(response.body(), "$.data.status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "MODERATOR"})
    void otherRolesCannotReadTagManagementInformation(String role) throws Exception {
        TagEntity tag = tag(actor("ADMIN"), TagStatus.ACTIVE);
        UserAccount reader = actor(role);
        clearInvocations(tags);

        assertProblem(get(tag.getId(), token(reader)), 403, "PERMISSION_DENIED");

        verifyNoInteractions(tags);
        assertTrue(redis.keys(PREFIX + "{tag-query}:*").isEmpty());
    }

    @Test
    void missingAndMalformedCredentialsCannotReadTags() throws Exception {
        TagEntity tag = tag(actor("ADMIN"), TagStatus.ACTIVE);
        clearInvocations(tags);
        for (String credential : java.util.Arrays.asList(null, "bad-token")) {
            HttpResponse<String> response = get(tag.getId(), credential);
            assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
        verifyNoInteractions(tags);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHANGED_AUTH_VERSION", "DISABLED", "UNVERIFIED"})
    void accountRestrictionsBeforeAuthenticationUseOnlyContractErrors(String change) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        changeAccount(admin, change);
        clearInvocations(tags);

        HttpResponse<String> response = get(tag.getId(), token);

        if ("CHANGED_AUTH_VERSION".equals(change)) assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
        else assertProblem(response, 403, "DISABLED".equals(change) ? "ACCOUNT_RESTRICTED" : "PERMISSION_DENIED");
        verifyNoInteractions(tags);
    }

    @Test
    void maximumLengthTagIdIsAccepted() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String tagId = "tag_" + id() + "_".repeat(28);
        assertEquals(64, tagId.length());
        jdbc.update("UPDATE community_tag SET id=? WHERE id=?", tagId, tag.getId());

        HttpResponse<String> response = get(tagId, token(admin));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(tagId, JsonPath.read(response.body(), "$.data.id"));
    }

    @Test
    void redisQueryFailureReturns503BeforeReadingTagData() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        clearInvocations(tags);
        doThrow(new RedisConnectionFailureException("sensitive connection details")).when(redis)
                .execute(any(RedisScript.class), argThat((List<String> keys) -> !keys.isEmpty()
                        && keys.get(0).startsWith(PREFIX + "{tag-query}:")), any(), any());

        HttpResponse<String> response = get(tag.getId(), token);

        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive connection details"));
        verifyNoInteractions(tags);
    }

    @Test
    void newlyCreatedTagsCanBeReadThroughTheirLocationWithoutAnIdempotencyKey() throws Exception {
        UserAccount admin = actor("ADMIN");
        String token = token(admin);
        String name = "Created_" + id().substring(0, 16);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/tags"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}")).build();
        var created = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        String tagId = JsonPath.read(created.body(), "$.data.id");
        assertEquals("/api/v1/admin/tags/" + tagId, created.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> response = get(tagId, token);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(name, JsonPath.read(response.body(), "$.data.name"));
        assertEquals(JsonPath.<Map<String, Object>>read(created.body(), "$.data"), JsonPath.read(response.body(), "$.data"));
    }

    @Test
    void openApiDeclaresTheAdminGetWithOnlyARequiredPathIdAndTheSharedTagSchema() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/admin/tags/{tagId}'].get");
        assertEquals("getAdminTag", operation.get("operationId"));
        assertFalse(operation.containsKey("requestBody"));
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) operation.get("parameters");
        assertEquals(1, parameters.size());
        assertEquals("tagId", parameters.get(0).get("name"));
        assertEquals("path", parameters.get(0).get("in"));
        assertEquals(true, parameters.get(0).get("required"));
        Map<?, ?> schema = (Map<?, ?>) parameters.get(0).get("schema");
        assertEquals("^[A-Za-z0-9_-]+$", schema.get("pattern"));
        assertEquals(1, ((Number) schema.get("minLength")).intValue());
        assertEquals(64, ((Number) schema.get("maxLength")).intValue());
        assertTrue(((List<Map<String, Object>>) operation.get("security")).stream().anyMatch(item -> item.containsKey("bearerAuth")));
        String wrapper = JsonPath.read(response.body(),
                "$.paths['/api/v1/admin/tags/{tagId}'].get.responses['200'].content['application/json'].schema['$ref']");
        String wrapperName = wrapper.substring(wrapper.lastIndexOf('/') + 1);
        assertEquals("#/components/schemas/Tag", JsonPath.read(response.body(),
                "$.components.schemas['" + wrapperName + "'].properties.data['$ref']"));
        assertEquals(Set.of("id", "name", "description", "status", "version"),
                new java.util.HashSet<>(JsonPath.<List<String>>read(response.body(), "$.components.schemas.Tag.required")));
    }

    private void changeAccount(UserAccount admin, String change) {
        switch (change) {
            case "REVOKED_ADMIN" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='ADMIN'", admin.getId());
            case "CHANGED_AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", admin.getId());
            case "DISABLED" -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", admin.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", admin.getId());
            case "WRITE_BLOCKED" -> jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", admin.getId());
            default -> throw new IllegalArgumentException("Unknown test account change: " + change);
        }
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
        user.setEmail("tag-query-" + user.getId() + "@example.test");
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
        tag.setName("Tag_" + id().substring(0, 20));
        tag.setDescription("标签说明");
        tag.setStatus(status);
        tag.setVersion(1);
        tag.setCreatedBy(creator.getId());
        tag.setCreatedAt(creator.getCreatedAt());
        tag.setUpdatedAt(creator.getCreatedAt());
        tags.insert(tag);
        return tag;
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "tag-query-test").accessToken();
    }

    private HttpResponse<String> get(String tagId, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/tags/" + tagId))
                .timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> row(String tagId) {
        return jdbc.queryForMap("SELECT * FROM community_tag WHERE id=?", tagId);
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
