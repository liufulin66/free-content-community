package com.freecontent.community.tag.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.tag.entity.TagEntity;
import com.freecontent.community.tag.entity.TagCreationRequestRecord;
import com.freecontent.community.tag.mapper.TagCreationRequestMapper;
import com.freecontent.community.tag.ratelimit.TagCreationRateLimiter;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class TagCreationFlowIT {
    private static final String PREFIX = "test:tag-create:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private TagCreationRequestMapper requests;
    @MockitoSpyBean private TagCreationRateLimiter limiter;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.tag.creation.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            for (String table : List.of("tag_creation_request", "community_tag")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                        Integer.class, table) == 1) jdbc.update("DELETE FROM " + table + " WHERE created_by=?", owner);
            }
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner); users.deleteById(owner);
        }
    }

    @Test void createsATrimmedTagWithDefaultsAndLocationBeforeReturning201() throws Exception {
        UserAccount admin = fixture("ADMIN"); String name = uniqueName();
        var response = create(token(admin), UUID.randomUUID().toString(), "{\"name\":\"  " + name + "  \"}");
        assertEquals(201, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "name", "description", "status", "version"), data.keySet());
        assertEquals(name, data.get("name")); assertEquals("", data.get("description"));
        assertEquals("ACTIVE", data.get("status")); assertEquals(1, ((Number) data.get("version")).intValue());
        String id = (String) data.get("id");
        assertEquals("/api/v1/admin/tags/" + id, response.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(name, jdbc.queryForObject("SELECT name FROM community_tag WHERE id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_request WHERE created_by=? AND state='SUCCEEDED'", Integer.class, admin.getId()));
    }

    @Test void unicodeWhitespaceCaseInsensitiveNamesAndDisabledNamesUseTheSameUniqueConstraint() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), name = uniqueName();
        var first = create(token, id(), body(Map.of("name", "\u00a0\u3000" + name + "\ufeff", "status", "DISABLED")));
        assertEquals(201, first.statusCode(), first.body()); assertEquals(name, JsonPath.read(first.body(), "$.data.name"));
        assertEquals("DISABLED", JsonPath.read(first.body(), "$.data.status"));
        assertProblem(create(token, id(), body(Map.of("name", name.toUpperCase(Locale.ROOT)))), 409, "TAG_NAME_CONFLICT");
        assertEquals(1, tagCount(admin));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_request WHERE created_by=?", Integer.class, admin.getId()));
    }

    @Test void accentDifferencesArePreservedAndUnicodeLimitsCountCodePoints() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), suffix = id().substring(0, 10);
        assertEquals(201, create(token, id(), body(Map.of("name", "Café_" + suffix))).statusCode());
        assertEquals(201, create(token, id(), body(Map.of("name", "Cafe_" + suffix))).statusCode());
        String name = "😀".repeat(22) + id().substring(0, 8), description = "😀".repeat(200);
        var result = create(token, id(), body(Map.of("name", name, "description", description)));
        assertEquals(201, result.statusCode(), result.body()); assertEquals(description, JsonPath.read(result.body(), "$.data.description"));
    }

    @Test void replayPreservesTheOriginalSnapshotAndRemainsAvailableAfterQuotaIsExhausted() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), key = id(), name = uniqueName();
        var first = create(token, key, body(Map.of("name", " " + name + " "))); assertEquals(201, first.statusCode());
        String tagId = JsonPath.read(first.body(), "$.data.id");
        jdbc.update("UPDATE community_tag SET description='changed',status='DISABLED',version=2 WHERE id=?", tagId);
        var replay = create(token, key, body(Map.of("status", "ACTIVE", "description", "", "name", name)));
        assertEquals(201, replay.statusCode(), replay.body());
        Map<String, Object> originalData = JsonPath.read(first.body(), "$.data"), replayData = JsonPath.read(replay.body(), "$.data");
        assertEquals(originalData, replayData); assertEquals(first.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        verify(limiter, times(1)).acquire(admin.getId(), "127.0.0.1");
        for (int i = 1; i < 100; i++) limiter.acquire(admin.getId(), "other-source-" + i);
        assertEquals(201, create(token, key, body(Map.of("name", name))).statusCode());
        assertProblem(create(token, id(), body(Map.of("name", uniqueName()))), 429, "RATE_LIMITED");
        assertEquals(1, tagCount(admin));
    }

    @Test void theSameKeyWithDifferentBusinessInputsIsRejected() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), key = id(), name = uniqueName();
        assertEquals(201, create(token, key, body(Map.of("name", name))).statusCode());
        for (Map<String, Object> changed : List.<Map<String, Object>>of(Map.of("name", uniqueName()),
                Map.of("name", name, "description", "changed"), Map.of("name", name, "status", "DISABLED"))) {
            assertProblem(create(token, key, body(changed)), 409, "IDEMPOTENCY_KEY_REUSED");
        }
        assertEquals(1, tagCount(admin));
    }

    @Test void administratorsHaveIndependentIdempotencyScopes() throws Exception {
        UserAccount first = fixture("ADMIN"), second = fixture("ADMIN"); String key = id();
        assertEquals(201, create(token(first), key, body(Map.of("name", uniqueName()))).statusCode());
        assertEquals(201, create(token(second), key, body(Map.of("name", uniqueName()))).statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "MODERATOR"})
    void nonAdministratorsAreRejectedBeforeJsonBinding(String role) throws Exception {
        UserAccount user = fixture(role);
        assertProblem(create(token(user), id(), "{invalid-json"), 403, "PERMISSION_DENIED"); assertEquals(0, tagCount(user));
    }

    @Test void anonymousRequestsNeedAuthenticationAndMalformedKeysCannotCreateTags() throws Exception {
        assertProblem(create(null, id(), "{}"), 401, "AUTHENTICATION_REQUIRED");
        UserAccount admin = fixture("ADMIN"); String token = token(admin), request = body(Map.of("name", uniqueName()));
        assertProblem(create(token, null, request), 400, "MALFORMED_REQUEST");
        for (String key : List.of("short", "x".repeat(129))) assertProblem(create(token, key, request), 422, "VALIDATION_ERROR");
        assertEquals(0, tagCount(admin));
    }

    @ParameterizedTest @ValueSource(strings = {"role", "auth_version", "status", "email"})
    void authorizationIsRecheckedAfterBearerAuthentication(String change) throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin);
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod(); revoke(admin, change); return principal;
        }).when(authentication).authenticate(token);
        var response = create(token, id(), body(Map.of("name", uniqueName())));
        assertProblem(response, "auth_version".equals(change) ? 401 : 403,
                "auth_version".equals(change) ? "AUTHENTICATION_REQUIRED" : "status".equals(change) ? "ACCOUNT_RESTRICTED" : "PERMISSION_DENIED");
        assertEquals(0, tagCount(admin));
    }

    @Test void losingAdminDuringQuotaCheckingPreventsTheFinalCommit() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin);
        doAnswer(invocation -> { invocation.callRealMethod(); revoke(admin, "role"); return null; })
                .when(limiter).acquire(admin.getId(), "127.0.0.1");
        assertProblem(create(token, id(), body(Map.of("name", uniqueName()))), 403, "PERMISSION_DENIED");
        assertEquals(0, tagCount(admin));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_request WHERE created_by=?", Integer.class, admin.getId()));
    }

    @Test void requestSnapshotFailureRollsBackTheInsertedTag() throws Exception {
        UserAccount admin = fixture("ADMIN");
        doThrow(new TransientDataAccessResourceException("simulated snapshot write failure"))
                .when(requests).complete(anyString(), anyString(), any(TagEntity.class), any());
        assertProblem(create(token(admin), id(), body(Map.of("name", uniqueName()))), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(0, tagCount(admin));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_request WHERE created_by=?", Integer.class, admin.getId()));
    }

    @Test void activeDuplicateRequestsReturnRequestInProgressAndLaterReplayOneTag() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), key = id(), body = body(Map.of("name", uniqueName()));
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1); pauseFirstQuotaCheck(entered, proceed);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> create(token, key, body)); assertTrue(entered.await(5, TimeUnit.SECONDS));
            var busy = create(token, key, body); assertProblem(busy, 409, "REQUEST_IN_PROGRESS");
            assertTrue(Long.parseLong(busy.headers().firstValue("Retry-After").orElseThrow()) >= 1);
            proceed.countDown(); var first = pending.get(15, TimeUnit.SECONDS); assertEquals(201, first.statusCode());
            String tagId = JsonPath.read(first.body(), "$.data.id");
            var replay = create(token, key, body); assertEquals(201, replay.statusCode()); assertEquals(tagId, JsonPath.read(replay.body(), "$.data.id"));
            assertEquals(1, tagCount(admin));
        } finally { proceed.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test void anExpiredLeaseCanBeRecoveredAndTheOldOwnerCannotOverwriteTheResult() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), key = id(), body = body(Map.of("name", uniqueName()));
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1); pauseFirstQuotaCheck(entered, proceed);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> create(token, key, body)); assertTrue(entered.await(5, TimeUnit.SECONDS));
            jdbc.update("UPDATE tag_creation_request SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE created_by=?", admin.getId());
            var recovered = create(token, key, body); assertEquals(201, recovered.statusCode(), recovered.body());
            proceed.countDown(); assertProblem(pending.get(15, TimeUnit.SECONDS), 409, "REQUEST_IN_PROGRESS");
            assertEquals(1, tagCount(admin));
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM tag_creation_request WHERE created_by=?", String.class, admin.getId()));
        } finally { proceed.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test void concurrentAdministratorsCreatingTheSameNameHaveOneWinner() throws Exception {
        TagCreationRequestMapper actualRequests = sqlSession.getMapper(TagCreationRequestMapper.class);
        var beforeInsert = new CyclicBarrier(2);
        doAnswer(invocation -> {
            beforeInsert.await(5, TimeUnit.SECONDS);
            TagCreationRequestRecord record = invocation.getArgument(0);
            return actualRequests.insert(record);
        }).when(requests).insert(any(TagCreationRequestRecord.class));
        UserAccount first = fixture("ADMIN"), second = fixture("ADMIN"); String tokenA = token(first), tokenB = token(second), name = uniqueName();
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return create(tokenA, id(), body(Map.of("name", name))); });
            var b = executor.submit(() -> { start.await(); return create(tokenB, id(), body(Map.of("name", name.toUpperCase(Locale.ROOT)))); });
            start.countDown(); var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(r -> r.statusCode() == 201).count());
            for (var result : results) if (result.statusCode() != 201) assertProblem(result, 409, "TAG_NAME_CONFLICT");
            assertEquals(1, tagCount(first) + tagCount(second));
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test void expiredKeysCanBeUsedForNewRequestsWithoutReplayingOldResults() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), key = id();
        assertEquals(201, create(token, key, body(Map.of("name", uniqueName()))).statusCode());
        jdbc.update("UPDATE tag_creation_request SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE created_by=?", admin.getId());
        assertEquals(201, create(token, key, body(Map.of("name", uniqueName()))).statusCode()); assertEquals(2, tagCount(admin));
    }

    @Test void cachedCreationResultsCannotBypassRevokedAdminPermission() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), key = id(), body = body(Map.of("name", uniqueName()));
        assertEquals(201, create(token, key, body).statusCode()); revoke(admin, "role");
        assertProblem(create(token, key, body), 403, "PERMISSION_DENIED"); assertEquals(1, tagCount(admin));
    }

    @Test void sameKeyConcurrentRequestsCreateOneTagAndOneSuccessfulSnapshot() throws Exception {
        UserAccount admin = fixture("ADMIN"); String token = token(admin), key = id(), body = body(Map.of("name", uniqueName()));
        var executor = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) pending.add(executor.submit(() -> { start.await(); return create(token, key, body); }));
            start.countDown(); Set<String> ids = new HashSet<>();
            for (var future : pending) {
                var response = future.get(20, TimeUnit.SECONDS);
                if (response.statusCode() == 201) ids.add(JsonPath.read(response.body(), "$.data.id"));
                else assertProblem(response, 409, "REQUEST_IN_PROGRESS");
            }
            assertEquals(1, ids.size()); assertEquals(1, tagCount(admin));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_request WHERE created_by=?", Integer.class, admin.getId()));
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test void openApiDeclaresTheCreationContractAndOptionalDefaults() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var result = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, result.statusCode());
        Map<String, Object> operation = JsonPath.read(result.body(), "$.paths['/api/v1/admin/tags'].post");
        assertEquals("createTag", operation.get("operationId"));
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertEquals(Set.of("201", "400", "401", "403", "409", "413", "415", "422", "429", "500", "503"), responses.keySet());
        Map<?, ?> headers = (Map<?, ?>) ((Map<?, ?>) responses.get("201")).get("headers");
        assertNotNull(headers); assertTrue(headers.keySet().containsAll(Set.of("Location", "X-Request-Id", "Cache-Control")));
        Map<String, Object> schema = JsonPath.read(result.body(), "$.components.schemas.TagWriteRequest");
        assertEquals(List.of("name"), schema.get("required")); assertEquals(false, schema.get("additionalProperties"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals("", ((Map<?, ?>) props.get("description")).get("default"));
        assertEquals("ACTIVE", ((Map<?, ?>) props.get("status")).get("default"));
    }

    private void revoke(UserAccount admin, String change) {
        if ("role".equals(change)) jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='ADMIN'", admin.getId());
        else if ("auth_version".equals(change)) jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", admin.getId());
        else if ("status".equals(change)) jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", admin.getId());
        else jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?", admin.getId());
    }
    private void pauseFirstQuotaCheck(CountDownLatch entered, CountDownLatch proceed) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) { entered.countDown(); if (!proceed.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test did not resume"); }
            return null;
        }).when(limiter).acquire(anyString(), anyString());
    }
    private String body(Map<String, Object> data) { return json.writeValueAsString(data); }
    private int tagCount(UserAccount actor) { return jdbc.queryForObject("SELECT COUNT(*) FROM community_tag WHERE created_by=?", Integer.class, actor.getId()); }
    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    private UserAccount fixture(String role) {
        UserAccount user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("tag-create-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        owners.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        if (!"USER".equals(role)) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,?)", user.getId(), role, user.getCreatedAt());
        return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "tag-create-test").accessToken(); }
    private HttpResponse<String> create(String token, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/tags"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String uniqueName() { return "Tag_" + id().substring(0, 20); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
