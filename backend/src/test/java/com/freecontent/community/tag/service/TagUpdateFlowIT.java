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
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.mybatis.spring.SqlSessionTemplate;
import com.freecontent.community.tag.dto.TagUpdateRequest;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.stream.Stream;
import java.util.concurrent.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class TagUpdateFlowIT {
    private static final String PREFIX = "test:tag-update:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @MockitoSpyBean private TagMapper tags;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private SqlSessionTemplate sqlSession;
    private final List<String> actors = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.query.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.update.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.update.user-hourly-limit", () -> 3);
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
    void administratorDisablesTagWithoutDeletingItOrChangingOmittedFields() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());

        var response = patch(tag.getId(), token(admin), "{\"expectedVersion\":1,\"status\":\"DISABLED\"}");

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "name", "description", "status", "version"), data.keySet());
        assertEquals(tag.getId(), data.get("id"));
        assertEquals("DISABLED", data.get("status"));
        assertEquals(2, data.get("version"));
        Map<String, Object> after = row(tag.getId());
        for (String column : List.of("id", "name", "description", "created_by", "created_at")) assertEquals(before.get(column), after.get(column));
        assertNotEquals(before.get("updated_at"), after.get("updated_at"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NAME", "DESCRIPTION", "STATUS"})
    void sameValuePatchPreservesVersionAndUpdateTime(String field) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());
        String body = switch (field) {
            case "NAME" -> "{\"expectedVersion\":1,\"name\":\"  " + tag.getName() + "  \"}";
            case "DESCRIPTION" -> "{\"expectedVersion\":1,\"description\":\"Original description\"}";
            default -> "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}";
        };

        var response = patch(tag.getId(), token(admin), body);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, (Integer) JsonPath.read(response.body(), "$.data.version"));
        assertEquals(before, row(tag.getId()));
    }

    @Test
    void staleVersionIsRejectedEvenWhenTheRequestedValueAlreadyMatches() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.DISABLED);
        jdbc.update("UPDATE community_tag SET version=2 WHERE id=?", tag.getId());
        Map<String, Object> before = row(tag.getId());

        var response = patch(tag.getId(), token(admin), "{\"expectedVersion\":1,\"status\":\"DISABLED\"}");

        assertProblem(response, 409, "RESOURCE_VERSION_CONFLICT");
        assertEquals(2, (Integer) JsonPath.read(response.body(), "$.currentVersion"));
        assertEquals(before, row(tag.getId()));
    }

    @Test
    void versionAtTheIntegerLimitDoesNotOverflowOnARealChange() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        jdbc.update("UPDATE community_tag SET version=2147483647 WHERE id=?", tag.getId());
        Map<String, Object> before = row(tag.getId());

        var response = patch(tag.getId(), token(admin), "{\"expectedVersion\":2147483647,\"status\":\"DISABLED\"}");

        assertProblem(response, 409, "RESOURCE_VERSION_CONFLICT");
        assertEquals(Integer.MAX_VALUE, (Integer) JsonPath.read(response.body(), "$.currentVersion"));
        assertEquals(before, row(tag.getId()));
    }

    @ParameterizedTest
    @MethodSource("invalidPatches")
    void invalidPatchesAreRejectedWithoutReadingOrChangingTags(String body, int status, String field) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());
        clearInvocations(tags);

        var response = patch(tag.getId(), token(admin), body);

        assertProblem(response, status, status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR");
        if (status == 422) {
            assertEquals("body", JsonPath.read(response.body(), "$.violations[0].location"));
            assertEquals(field, JsonPath.read(response.body(), "$.violations[0].path"));
        }
        assertEquals(before, row(tag.getId()));
        verifyNoInteractions(tags);
        assertTrue(redis.keys(PREFIX + "{tag-update}:*").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?status=ACTIVE", "?page=1", "?expectedVersion=99", "?status=ACTIVE&status=DISABLED"})
    void unknownQueryParametersAreRejectedBeforeChangingTags(String query) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());
        clearInvocations(tags);

        var response = patch(tag.getId() + query, token(admin), "{\"expectedVersion\":1,\"status\":\"DISABLED\"}");

        assertProblem(response, 400, "MALFORMED_REQUEST");
        assertEquals("query", JsonPath.read(response.body(), "$.violations[0].location"));
        assertEquals(query.substring(1).split("=", 2)[0], JsonPath.read(response.body(), "$.violations[0].path"));
        verifyNoInteractions(tags);
        assertEquals(before, row(tag.getId()));
        assertTrue(redis.keys(PREFIX + "{tag-update}:*").isEmpty());
    }

    static Stream<Arguments> invalidPatches() {
        return Stream.of(
                Arguments.of("{}", 422, "expectedVersion"),
                Arguments.of("{\"status\":\"DISABLED\"}", 422, "expectedVersion"),
                Arguments.of("{\"expectedVersion\":null,\"status\":\"DISABLED\"}", 422, "expectedVersion"),
                Arguments.of("{\"expectedVersion\":0,\"status\":\"DISABLED\"}", 422, "expectedVersion"),
                Arguments.of("{\"expectedVersion\":-1,\"status\":\"DISABLED\"}", 422, "expectedVersion"),
                Arguments.of("{\"expectedVersion\":1}", 422, ""),
                Arguments.of("{\"expectedVersion\":1,\"name\":null}", 422, "name"),
                Arguments.of("{\"expectedVersion\":1,\"description\":null}", 422, "description"),
                Arguments.of("{\"expectedVersion\":1,\"status\":null}", 422, "status"),
                Arguments.of("{\"expectedVersion\":1,\"name\":\"   \"}", 422, "name"),
                Arguments.of("{\"expectedVersion\":\"1\",\"status\":\"DISABLED\"}", 400, ""),
                Arguments.of("{\"expectedVersion\":1.0,\"status\":\"DISABLED\"}", 400, ""),
                Arguments.of("{\"expectedVersion\":true,\"status\":\"DISABLED\"}", 400, ""),
                Arguments.of("{\"expectedVersion\":2147483648,\"status\":\"DISABLED\"}", 422, "expectedVersion"),
                Arguments.of("{\"expectedVersion\":1,\"name\":123}", 400, ""),
                Arguments.of("{\"expectedVersion\":1,\"description\":true}", 400, ""),
                Arguments.of("{\"expectedVersion\":1,\"status\":0}", 400, ""),
                Arguments.of("{\"expectedVersion\":1,\"status\":\"0\"}", 400, ""),
                Arguments.of("{\"expectedVersion\":1,\"status\":\"OTHER\"}", 400, ""),
                Arguments.of("{\"expectedVersion\":1,\"status\":\"DISABLED\",\"createdBy\":\"other\"}", 400, ""),
                Arguments.of("{\"expectedVersion\":1,\"status\":\"DISABLED\",\"nameProvided\":true}", 400, ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad.tag", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidTagIdsHavePathViolationsBeforeDatabaseAccess(String tagId) throws Exception {
        UserAccount admin = actor("ADMIN");
        clearInvocations(tags);
        var response = patch(tagId, token(admin), "{\"expectedVersion\":1,\"status\":\"DISABLED\"}");
        assertProblem(response, 422, "VALIDATION_ERROR");
        assertEquals("path", JsonPath.read(response.body(), "$.violations[0].location"));
        assertEquals("tagId", JsonPath.read(response.body(), "$.violations[0].path"));
        verifyNoInteractions(tags);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVOKED_ADMIN", "CHANGED_AUTH_VERSION", "DISABLED", "UNVERIFIED", "WRITE_BLOCKED"})
    void accountAndRoleChangesAfterAuthenticationCannotModifyTags(String change) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());
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

        var response = patch(tag.getId(), token, "{\"expectedVersion\":1,\"status\":\"DISABLED\"}");

        if ("CHANGED_AUTH_VERSION".equals(change)) assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
        else assertProblem(response, 403, List.of("DISABLED", "WRITE_BLOCKED").contains(change) ? "ACCOUNT_RESTRICTED" : "PERMISSION_DENIED");
        assertEquals(before, row(tag.getId()));
        verifyNoInteractions(tags);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DISABLED"})
    void duplicateNamesIgnoringCaseReturn409AndRollBackAllRequestedChanges(String status) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity target = tag(admin, TagStatus.ACTIVE), existing = tag(actor("ADMIN"), TagStatus.valueOf(status));
        Map<String, Object> before = row(target.getId());
        String body = json.writeValueAsString(Map.of("expectedVersion", 1,
                "name", existing.getName().toUpperCase(java.util.Locale.ROOT), "description", "Changed", "status", "DISABLED"));

        var response = patch(target.getId(), token(admin), body);

        assertProblem(response, 409, "TAG_NAME_CONFLICT");
        assertFalse(response.body().contains("currentVersion"));
        assertEquals(before, row(target.getId()));
    }

    @Test
    void databaseReadFailureReturns503WithoutInternalDetails() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        doThrow(new TransientDataAccessResourceException("sensitive database details")).when(tags).findByIdForUpdate(tag.getId());

        var response = patch(tag.getId(), token(admin), "{\"expectedVersion\":1,\"status\":\"DISABLED\"}");

        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive database details"));
    }

    @Test
    void failureAfterTheSqlUpdateRollsBackFieldsAndVersion() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());
        doAnswer(invocation -> {
            int changed = sqlSession.update(TagMapper.class.getName() + ".updateFields", Map.of(
                    "tagId", invocation.getArgument(0), "expectedVersion", invocation.getArgument(1),
                    "patch", invocation.getArgument(2), "now", invocation.getArgument(3)));
            assertEquals(1, changed);
            throw new TransientDataAccessResourceException("simulated failure after update");
        }).when(tags).updateFields(eq(tag.getId()), anyInt(), any(TagUpdateRequest.class), any());

        var response = patch(tag.getId(), token(admin), "{\"expectedVersion\":1,\"description\":\"Changed\",\"status\":\"DISABLED\"}");

        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(before, row(tag.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOOP", "VERSION_CONFLICT"})
    void noOpAndVersionConflictRequestsConsumeTheIndependentUpdateQuota(String kind) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        Map<String, Object> before = row(tag.getId());
        String token = token(admin);
        String body = "NOOP".equals(kind) ? "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"
                : "{\"expectedVersion\":2,\"status\":\"DISABLED\"}";
        for (int i = 0; i < 3; i++) {
            var response = patch(tag.getId(), token, body);
            if ("NOOP".equals(kind)) assertEquals(200, response.statusCode(), response.body());
            else assertProblem(response, 409, "RESOURCE_VERSION_CONFLICT");
        }

        var limited = patch(tag.getId(), token, body);

        assertProblem(limited, 429, "RATE_LIMITED");
        long retry = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retry >= 1 && retry <= 3600);
        assertEquals(before, row(tag.getId()));
    }

    @Test
    void concurrentAdministratorsCannotBothChangeTheSameVersion() throws Exception {
        UserAccount first = actor("ADMIN"), second = actor("ADMIN");
        TagEntity tag = tag(first, TagStatus.ACTIVE);
        String firstToken = token(first), secondToken = token(second);
        CyclicBarrier ready = new CyclicBarrier(2);
        doAnswer(invocation -> {
            ready.await(5, TimeUnit.SECONDS);
            return sqlSession.selectOne(TagMapper.class.getName() + ".findByIdForUpdate", Map.of("tagId", invocation.getArgument(0)));
        }).when(tags).findByIdForUpdate(tag.getId());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> a = workers.submit(() -> patch(tag.getId(), firstToken, "{\"expectedVersion\":1,\"status\":\"DISABLED\"}"));
            Future<HttpResponse<String>> b = workers.submit(() -> patch(tag.getId(), secondToken, "{\"expectedVersion\":1,\"description\":\"Changed\"}"));
            var responses = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
            var conflict = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
            assertProblem(conflict, 409, "RESOURCE_VERSION_CONFLICT");
            assertEquals(2, (Integer) JsonPath.read(conflict.body(), "$.currentVersion"));
            var after = row(tag.getId());
            assertEquals(2, after.get("version"));
            assertTrue(("DISABLED".equals(after.get("status")) && "Original description".equals(after.get("description")))
                    || ("ACTIVE".equals(after.get("status")) && "Changed".equals(after.get("description"))));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void concurrentRenamesToOneNameProduceOneSuccessAndOneNameConflict() throws Exception {
        UserAccount first = actor("ADMIN"), second = actor("ADMIN");
        TagEntity aTag = tag(first, TagStatus.ACTIVE), bTag = tag(second, TagStatus.ACTIVE);
        String aToken = token(first), bToken = token(second);
        String body = json.writeValueAsString(Map.of("expectedVersion", 1, "name", "Shared_" + id().substring(0, 20)));
        CyclicBarrier ready = new CyclicBarrier(2);
        doAnswer(invocation -> {
            ready.await(5, TimeUnit.SECONDS);
            return sqlSession.update(TagMapper.class.getName() + ".updateFields", Map.of(
                    "tagId", invocation.getArgument(0), "expectedVersion", invocation.getArgument(1),
                    "patch", invocation.getArgument(2), "now", invocation.getArgument(3)));
        }).when(tags).updateFields(anyString(), anyInt(), any(TagUpdateRequest.class), any());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> patch(aTag.getId(), aToken, body));
            var b = workers.submit(() -> patch(bTag.getId(), bToken, body));
            var responses = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList(), responses.toString());
            assertProblem(responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(), 409, "TAG_NAME_CONFLICT");
            assertEquals(List.of(1, 2), List.of((Integer) row(aTag.getId()).get("version"), (Integer) row(bTag.getId()).get("version"))
                    .stream().sorted().toList());
        } finally { workers.shutdownNow(); }
    }

    @Test
    void anotherAdministratorCanClearDescriptionAdjustNameCaseAndReenableTheTag() throws Exception {
        UserAccount creator = actor("ADMIN"), editor = actor("ADMIN");
        TagEntity tag = tag(creator, TagStatus.ACTIVE);
        String token = token(editor), name = "java_" + id().substring(0, 20);
        String body = json.writeValueAsString(Map.of("expectedVersion", 1, "name", "　" + name + "　", "description", "", "status", "DISABLED"));
        var changed = patch(tag.getId(), token, body);
        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals(name, JsonPath.read(changed.body(), "$.data.name"));
        assertEquals("", JsonPath.read(changed.body(), "$.data.description"));
        assertEquals(2, (Integer) JsonPath.read(changed.body(), "$.data.version"));
        String uppercase = name.toUpperCase(java.util.Locale.ROOT);
        var caseChange = patch(tag.getId(), token, json.writeValueAsString(Map.of("expectedVersion", 2, "name", uppercase)));
        assertEquals(200, caseChange.statusCode(), caseChange.body());
        assertEquals(uppercase, JsonPath.read(caseChange.body(), "$.data.name"));
        assertEquals(3, (Integer) JsonPath.read(caseChange.body(), "$.data.version"));
        var enabled = patch(tag.getId(), token, "{\"expectedVersion\":3,\"status\":\"ACTIVE\"}");
        assertEquals(200, enabled.statusCode(), enabled.body());
        assertEquals("ACTIVE", JsonPath.read(enabled.body(), "$.data.status"));
        assertEquals(4, (Integer) JsonPath.read(enabled.body(), "$.data.version"));
        assertEquals(creator.getId(), row(tag.getId()).get("created_by"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NAME", "DESCRIPTION"})
    void unicodeCharacterLimitsAreCountedByCodePoint(String field) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        String property = "NAME".equals(field) ? "name" : "description";
        String valid = "NAME".equals(field) ? "😀".repeat(20) + id().substring(0, 10) : "😀".repeat(200);
        var response = patch(tag.getId(), token, json.writeValueAsString(Map.of("expectedVersion", 1, property, valid)));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(valid, JsonPath.read(response.body(), "$.data." + property));
        String invalid = "😀".repeat("NAME".equals(field) ? 31 : 201);
        assertProblem(patch(tag.getId(), token, json.writeValueAsString(Map.of("expectedVersion", 2, property, invalid))), 422, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "MODERATOR"})
    void otherRolesCannotModifyTagsOrSeeTheirCurrentVersion(String role) throws Exception {
        TagEntity tag = tag(actor("ADMIN"), TagStatus.ACTIVE);
        UserAccount caller = actor(role);
        Map<String, Object> before = row(tag.getId());
        clearInvocations(tags);
        var response = patch(tag.getId(), token(caller), "{\"expectedVersion\":99,\"status\":\"DISABLED\"}");
        assertProblem(response, 403, "PERMISSION_DENIED");
        assertFalse(response.body().contains("currentVersion"));
        assertEquals(before, row(tag.getId()));
        verifyNoInteractions(tags);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRITE_BLOCKED", "DISABLED", "UNVERIFIED"})
    void accountRestrictionsBeforeAuthenticationRejectEvenNoOpPatches(String restriction) throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        if ("UNVERIFIED".equals(restriction)) jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", admin.getId());
        else jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", restriction, admin.getId());
        clearInvocations(tags);
        assertProblem(patch(tag.getId(), token, "{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"),
                403, "UNVERIFIED".equals(restriction) ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        verifyNoInteractions(tags);
    }

    @Test
    void unknownOrWrongCaseIdsAre404AndNoCredentialsAre401() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String body = "{\"expectedVersion\":1,\"status\":\"DISABLED\"}", token = token(admin);
        assertProblem(patch("missing_" + id(), token, body), 404, "RESOURCE_NOT_FOUND");
        assertProblem(patch(tag.getId().toUpperCase(java.util.Locale.ROOT), token, body), 404, "RESOURCE_NOT_FOUND");
        for (String credential : java.util.Arrays.asList(null, "bad-token")) {
            var response = patch(tag.getId(), credential, body);
            assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
    }

    @Test
    void redisFailurePreventsTheTagTransactionAndReturns503() throws Exception {
        UserAccount admin = actor("ADMIN");
        TagEntity tag = tag(admin, TagStatus.ACTIVE);
        String token = token(admin);
        clearInvocations(tags);
        doThrow(new RedisConnectionFailureException("sensitive redis details")).when(redis)
                .execute(any(RedisScript.class), argThat((List<String> keys) -> !keys.isEmpty()
                        && keys.get(0).startsWith(PREFIX + "{tag-update}:")), any(), any());
        var response = patch(tag.getId(), token, "{\"expectedVersion\":1,\"status\":\"DISABLED\"}");
        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive redis details"));
        verifyNoInteractions(tags);
    }

    @Test
    void originalCreationReplayRemainsAFrozenReceiptAfterTagModification() throws Exception {
        UserAccount admin = actor("ADMIN");
        String token = token(admin), key = UUID.randomUUID().toString();
        String name = "Created_" + id().substring(0, 20), changedName = "Changed_" + id().substring(0, 20);
        String body = json.writeValueAsString(Map.of("name", name));
        var create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/tags"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var created = client.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        String tagId = JsonPath.read(created.body(), "$.data.id");
        String counter = PREFIX + "{tag-create}:user:" + com.freecontent.community.auth.security.TokenDigest.sha256(admin.getId());
        String creationCount = redis.opsForValue().get(counter);
        var changed = patch(tagId, token, json.writeValueAsString(Map.of("expectedVersion", 1, "name", changedName, "status", "DISABLED")));
        assertEquals(200, changed.statusCode(), changed.body());

        var replay = client.send(create, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String, Object>>read(created.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
        assertEquals(creationCount, redis.opsForValue().get(counter));
        var read = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/tags/" + tagId))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).GET().build();
        var current = client.send(read, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, current.statusCode(), current.body());
        assertEquals(changedName, JsonPath.read(current.body(), "$.data.name"));
        assertEquals("DISABLED", JsonPath.read(current.body(), "$.data.status"));
        assertEquals(2, (Integer) JsonPath.read(current.body(), "$.data.version"));
    }

    @Test
    void openApiDeclaresTheVersionedPatchWithoutAnIdempotencyHeaderOrInternalFlags() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/admin/tags/{tagId}'].patch");
        assertEquals("updateTag", operation.get("operationId"));
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) operation.get("parameters");
        assertEquals(1, parameters.size());
        assertEquals("tagId", parameters.get(0).get("name"));
        assertEquals("path", parameters.get(0).get("in"));
        assertEquals("#/components/schemas/TagUpdateRequest", JsonPath.read(response.body(),
                "$.paths['/api/v1/admin/tags/{tagId}'].patch.requestBody.content['application/json'].schema['$ref']"));
        Map<String, Object> schema = JsonPath.read(response.body(), "$.components.schemas.TagUpdateRequest");
        assertEquals(Set.of("expectedVersion"), new java.util.HashSet<>((List<String>) schema.get("required")));
        assertEquals(2, ((Number) schema.get("minProperties")).intValue());
        assertEquals(false, schema.get("additionalProperties"));
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");
        assertEquals(Set.of("expectedVersion", "name", "description", "status"), properties.keySet());
        assertEquals("integer", properties.get("expectedVersion").get("type"));
        assertEquals(Integer.MAX_VALUE, ((Number) properties.get("expectedVersion").get("maximum")).intValue());
        assertEquals(30, ((Number) properties.get("name").get("maxLength")).intValue());
        assertEquals(200, ((Number) properties.get("description").get("maxLength")).intValue());
        assertEquals(Set.of("ACTIVE", "DISABLED"), new java.util.HashSet<>((List<String>) properties.get("status").get("enum")));
        for (String field : List.of("name", "description", "status")) assertFalse(properties.get(field).containsKey("default"));
        assertEquals(Set.of("200", "400", "401", "403", "404", "409", "413", "415", "422", "429", "500", "503"),
                ((Map<?, ?>) operation.get("responses")).keySet());
        assertNotNull(JsonPath.read(response.body(),
                "$.paths['/api/v1/admin/tags/{tagId}'].patch.responses['200'].content['application/json'].schema['$ref']"));
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
        user.setEmail("tag-update-" + user.getId() + "@example.test");
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
        tag.setName("Update_" + id().substring(0, 20));
        tag.setDescription("Original description");
        tag.setStatus(status);
        tag.setVersion(1);
        tag.setCreatedBy(creator.getId());
        tag.setCreatedAt(creator.getCreatedAt());
        tag.setUpdatedAt(creator.getCreatedAt());
        tags.insert(tag);
        return tag;
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "tag-update-test").accessToken();
    }

    private HttpResponse<String> patch(String tagId, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/tags/" + tagId))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> row(String tagId) { return jdbc.queryForMap("SELECT * FROM community_tag WHERE id=?", tagId); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
