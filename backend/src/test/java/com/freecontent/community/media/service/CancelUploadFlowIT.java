package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.entity.UploadCleanupTask;
import com.freecontent.community.media.mapper.UploadCleanupTaskMapper;
import com.freecontent.community.media.ratelimit.UploadCancellationRateLimiter;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class CancelUploadFlowIT {
    private static final String PREFIX = "test:cancel-upload-it:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private UploadCleanupTaskMapper tasks;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private UploadCancellationRateLimiter limiter;
    private final List<String> userIds = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-cancel.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : userIds) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='media_upload_cleanup'", Integer.class) == 1) {
                jdbc.update("DELETE FROM media_upload_cleanup WHERE owner_id=?", id);
            }
            for (String table : List.of("media_upload_request", "media_upload_session", "media_asset")) jdbc.update("DELETE FROM " + table + " WHERE owner_id=?", id);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void cancellationCommitsStateAndDurableDelayedCleanupBeforeReturning204() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        HttpResponse<String> created = create(token, UUID.randomUUID().toString());
        assertEquals(201, created.statusCode());
        String id = JsonPath.read(created.body(), "$.data.id");
        String assetId = JsonPath.read(created.body(), "$.data.assetId");
        Instant deadline = Instant.parse(JsonPath.read(created.body(), "$.data.expiresAt"));
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM media_upload_session WHERE id=?", id);

        HttpResponse<String> response = cancel(id, "1", token);

        assertEquals(204, response.statusCode());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, id));
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, assetId));
        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM media_upload_session WHERE id=?", id);
        for (String field : List.of("storage_key", "signature_at", "signature_ttl_seconds", "signer_id", "expires_at")) assertEquals(before.get(field), after.get(field));
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM media_upload_cleanup WHERE upload_id=?", String.class, id));
        assertEquals(LocalDateTime.ofInstant(deadline.plusSeconds(600), ZoneOffset.UTC), jdbc.queryForObject("SELECT available_at FROM media_upload_cleanup WHERE upload_id=?", LocalDateTime.class, id));
        HttpResponse<String> view = request("GET", "/api/v1/media/uploads/" + id, token);
        assertEquals(200, view.statusCode());
        assertTrue(JsonPath.read(view.body(), "$.data.singleUploadTarget") == null, "Cancellation must stop renewal");
    }

    @Test
    void originalCreationKeyCannotReturnAnUploadCredentialAfterCancellation() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        String id = JsonPath.read(create(token, key).body(), "$.data.id");
        assertEquals(204, cancel(id, "1", token).statusCode());
        HttpResponse<String> replay = create(token, key);
        assertEquals(403, replay.statusCode());
        assertEquals("PERMISSION_DENIED", JsonPath.read(replay.body(), "$.code"));
        assertTrue(!replay.body().contains("singleUploadTarget"));
    }

    @Test
    void repeatedCancellationAcceptsTheOriginalVersionWithoutDuplicatingTasks() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        assertEquals(204, cancel(id, "1", token).statusCode());
        assertEquals(204, cancel(id, "1", token).statusCode());
        assertEquals(204, cancel(id, "100", token).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "EXPIRED"})
    void completedOrExpiredSessionsCannotBeCancelled(String state) throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        jdbc.update("UPDATE media_upload_session SET state=? WHERE id=?", state, id);
        HttpResponse<String> response = cancel(id, "1", token);
        assertEquals(409, response.statusCode());
        assertEquals("UPLOAD_STATE_CONFLICT", JsonPath.read(response.body(), "$.code"));
        assertEquals(state, jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @Test
    void staleVersionAndElapsedDeadlineCannotChangeTheUpload() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        HttpResponse<String> stale = cancel(id, "2", token);
        assertEquals(409, stale.statusCode());
        assertEquals(1, (Integer) JsonPath.read(stale.body(), "$.currentVersion"));
        jdbc.update("UPDATE media_upload_session SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
        assertEquals(409, cancel(id, "1", token).statusCode());
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "PROCESSING"})
    void anAlreadyUsableOrProcessingAssetIsNotDeletedThroughCancellation(String state) throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        HttpResponse<String> created = create(token, UUID.randomUUID().toString());
        String id = JsonPath.read(created.body(), "$.data.id"), assetId = JsonPath.read(created.body(), "$.data.assetId");
        jdbc.update("UPDATE media_asset SET state=? WHERE id=?", state, assetId);
        assertEquals(409, cancel(id, "1", token).statusCode());
        assertEquals(state, jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, assetId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @Test
    void invalidIdAndOutOfRangeVersionsHaveExplicitFieldViolations() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        HttpResponse<String> badId = cancel("invalid.id", "1", token);
        assertEquals(422, badId.statusCode());
        assertEquals("path", JsonPath.read(badId.body(), "$.violations[0].location"));
        for (String version : List.of("0", "-1", "2147483648", "-2147483649", "9223372036854775808")) {
            HttpResponse<String> response = cancel(id, version, token);
            assertEquals(422, response.statusCode());
            assertEquals("query", JsonPath.read(response.body(), "$.violations[0].location"));
            assertEquals("expectedVersion", JsonPath.read(response.body(), "$.violations[0].path"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @Test
    void missingAndUnparseableVersionAre400WithoutChangingTheSession() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        assertEquals(400, cancel(id, null, token).statusCode());
        for (String version : List.of("1.5", "abc")) assertEquals(400, cancel(id, version, token).statusCode());
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, id));
    }

    @Test
    void invalidIdentityAndOtherOwnersCannotCancelTheUpload() throws Exception {
        UserAccount owner = fixture(), other = fixture();
        String token = token(owner);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        assertEquals(401, cancel(id, "1", null).statusCode());
        assertEquals(404, cancel(id, "1", token(other)).statusCode());
        assertEquals(404, cancel("unknown", "1", token).statusCode());
        for (String status : List.of("WRITE_BLOCKED", "DISABLED")) {
            jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", status, owner.getId());
            assertEquals(403, cancel(id, "1", token).statusCode());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @Test void enqueueFailureRollsBackTheCancellationAndAssetState() throws Exception {
        UserAccount user = fixture(); String token = token(user);
        HttpResponse<String> created = create(token, UUID.randomUUID().toString());
        String id = JsonPath.read(created.body(), "$.data.id"), assetId = JsonPath.read(created.body(), "$.data.assetId");
        doThrow(new TransientDataAccessResourceException("simulated enqueue failure")).when(tasks).insert(any(UploadCleanupTask.class));
        assertEquals(503, cancel(id, "1", token).statusCode());
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, id));
        assertEquals("WAITING_UPLOAD", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, assetId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @Test void credentialChangeAfterAuthenticationCannotCancelTheSession() throws Exception {
        UserAccount user = fixture(); String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", user.getId()); return principal;
        }).when(authentication).authenticate(token);
        assertEquals(401, cancel(id, "1", token).statusCode());
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_AUTHENTICATION", "AFTER_AUTHENTICATION"})
    void unverifiedEmailCannotCancelUploadAndUsesTheContractCode(String timing) throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM media_upload_session WHERE id=?", id);
        Map<String, Object> assetBefore = jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?", before.get("asset_id"));
        if ("BEFORE_AUTHENTICATION".equals(timing)) {
            jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", user.getId());
        } else {
            doAnswer(invocation -> {
                SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
                jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", user.getId());
                return principal;
            }).when(authentication).authenticate(token);
        }

        HttpResponse<String> response = cancel(id, "1", token);

        assertEquals(403, response.statusCode());
        assertEquals("PERMISSION_DENIED", JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM media_upload_session WHERE id=?", id));
        assertEquals(assetBefore, jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?", before.get("asset_id")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @Test void simultaneousCancellationsChangeTheVersionOnlyOnce() throws Exception {
        UserAccount user = fixture(); String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<HttpResponse<String>> first = executor.submit(() -> { start.await(); return cancel(id, "1", token); });
            Future<HttpResponse<String>> second = executor.submit(() -> { start.await(); return cancel(id, "1", token); });
            start.countDown();
            assertEquals(204, first.get(15, TimeUnit.SECONDS).statusCode()); assertEquals(204, second.get(15, TimeUnit.SECONDS).statusCode());
        } finally { executor.shutdownNow(); }
        assertEquals(2, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, id));
    }

    @Test void cancellationAndCompletionCannotBothWinTheSameVersion() throws Exception {
        UserAccount user = fixture(); String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<HttpResponse<String>> cancel = executor.submit(() -> { start.await(); return cancel(id, "1", token); });
            Future<Integer> complete = executor.submit(() -> { start.await(); return jdbc.update("UPDATE media_upload_session SET state='COMPLETED',version=version+1 WHERE id=? AND state='UPLOADING' AND version=1", id); });
            start.countDown(); HttpResponse<String> result = cancel.get(15, TimeUnit.SECONDS); int completed = complete.get(15, TimeUnit.SECONDS);
            assertTrue((result.statusCode()==204 && completed==0) || (result.statusCode()==409 && completed==1));
        } finally { executor.shutdownNow(); }
        assertEquals(2, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, id));
    }

    @Test void repeatedCancellationConsumesOnlyItsIndependentMinuteQuota() throws Exception {
        UserAccount user = fixture(); String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        for (int i=0;i<59;i++) limiter.acquire(user.getId(), "test-source-"+i);
        assertEquals(204, cancel(id, "1", token).statusCode());
        HttpResponse<String> limited = cancel(id, "1", token);
        assertEquals(429, limited.statusCode());
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        assertEquals(200, request("GET", "/api/v1/media/uploads/"+id, token).statusCode());
    }

    @Test void openApiDocumentsBodylessDeleteWithRequiredQueryVersion() throws Exception {
        var response = request("GET", "/v3/api-docs", null); assertEquals(200,response.statusCode());
        Map<?,?> operation=JsonPath.read(response.body(),"$.paths['/api/v1/media/uploads/{uploadId}'].delete");
        assertEquals("cancelMyUpload",operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        Map<?,?> noContent=(Map<?,?>)((Map<?,?>)operation.get("responses")).get("204");
        assertNotNull(noContent); assertFalse(noContent.containsKey("content"));
        List<?> parameters=(List<?>)operation.get("parameters"); assertEquals(2,parameters.size());
        Map<?,?> version=(Map<?,?>)parameters.stream().filter(value->"expectedVersion".equals(((Map<?,?>)value).get("name"))).findFirst().orElseThrow();
        assertEquals("query",version.get("in")); assertEquals(true,version.get("required"));
        Map<?,?> schema=(Map<?,?>)version.get("schema"); assertEquals("integer",schema.get("type")); assertEquals(1,((Number)schema.get("minimum")).intValue());
    }

    private UserAccount fixture() {
        UserAccount user = LoginTestSupport.account();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail("cancel-upload-it-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        userIds.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "cancel-test").accessToken(); }
    private HttpResponse<String> create(String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/media/uploads"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("scene", "CONTENT_IMAGE", "fileName", "image.png", "contentType", "image/png", "sizeBytes", 68))));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> cancel(String id, String version, String token) throws Exception {
        return request("DELETE", "/api/v1/media/uploads/" + id + (version == null ? "" : "?expectedVersion=" + version), token);
    }
    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
