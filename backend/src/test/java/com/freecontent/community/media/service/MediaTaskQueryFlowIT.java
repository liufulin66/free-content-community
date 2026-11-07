package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import com.freecontent.community.media.storage.UploadObjectInspector;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false",
        "app.media.task-query.user-per-minute-limit=3"})
class MediaTaskQueryFlowIT {
    private static final String PREFIX = "test:media-task-query:" + UUID.randomUUID() + ":";
    private static final LocalDateTime CREATED = LocalDateTime.ofInstant(Instant.parse("2026-11-07T00:00:00.123456Z"), ZoneOffset.UTC);
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private MediaAssetMapper assets;
    @Autowired private MediaUploadMapper uploads;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @MockitoSpyBean private MediaProcessingTaskMapper tasks;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private UploadObjectInspector inspector;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.task-query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_upload_completion_request WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM media_upload_cleanup WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM media_processing_task WHERE owner_id=? AND retry_of_task_id IS NOT NULL", owner);
            jdbc.update("DELETE FROM media_processing_task WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM media_upload_request WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM media_upload_session WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"})
    void returnsTheStoredStateAndContractFieldsWithoutChangingTheTask(String state) throws Exception {
        Fixture fixture = fixture();
        int progress = "SUCCEEDED".equals(state) ? 100 : "RUNNING".equals(state) || "FAILED".equals(state) ? 45 : 0;
        boolean retryable = "FAILED".equals(state);
        String errorCode = retryable ? "MEDIA_STORAGE_UNAVAILABLE" : null;
        String errorMessage = retryable ? "存储暂不可用，请稍后重试" : null;
        LocalDateTime updated = CREATED.plusSeconds(3);
        jdbc.update("UPDATE media_processing_task SET state=?,progress_percent=?,retryable=?,error_code=?,error_message=?,updated_at=? WHERE id=?",
                state, progress, retryable, errorCode, errorMessage, updated, fixture.taskId());
        String assetState = "SUCCEEDED".equals(state) ? "READY" : Set.of("FAILED", "CANCELLED").contains(state) ? "FAILED" : "PROCESSING";
        jdbc.update("UPDATE media_asset SET state=? WHERE id=?", assetState, fixture.assetId());
        Map<String, Object> before = taskRow(fixture.taskId());

        var response = get(fixture.taskId(), fixture.token());

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "type", "state", "progressPercent", "assetId", "retryable", "errorCode", "errorMessage",
                "createdAt", "updatedAt", "retryOfTaskId"), data.keySet());
        assertEquals(fixture.taskId(), data.get("id")); assertEquals("MEDIA_PROCESSING", data.get("type"));
        assertEquals(state, data.get("state")); assertEquals(progress, ((Number) data.get("progressPercent")).intValue());
        assertEquals(fixture.assetId(), data.get("assetId")); assertEquals(Boolean.valueOf(retryable), data.get("retryable"));
        assertEquals(errorCode, data.get("errorCode")); assertEquals(errorMessage, data.get("errorMessage"));
        assertNull(data.get("retryOfTaskId"));
        assertEquals(CREATED.toInstant(ZoneOffset.UTC), Instant.parse((String) data.get("createdAt")));
        assertEquals(updated.toInstant(ZoneOffset.UTC), Instant.parse((String) data.get("updatedAt")));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.<String>read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertNotNull(JsonPath.read(response.body(), "$.serverTime"));
        assertEquals(before, taskRow(fixture.taskId()));
        assertEquals(assetState, jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, fixture.assetId()));
        verifyNoInteractions(inspector);
    }

    @Test
    void readsTheCurrentCommittedStateOnEveryRequest() throws Exception {
        Fixture fixture = fixture();
        var first = get(fixture.taskId(), fixture.token());
        assertEquals(200, first.statusCode()); assertEquals("PENDING", JsonPath.read(first.body(), "$.data.state"));
        jdbc.update("UPDATE media_processing_task SET state='RUNNING',progress_percent=40 WHERE id=?", fixture.taskId());
        var current = get(fixture.taskId(), fixture.token());
        assertEquals(200, current.statusCode()); assertEquals("RUNNING", JsonPath.read(current.body(), "$.data.state"));
        assertEquals(40, ((Number) JsonPath.read(current.body(), "$.data.progressPercent")).intValue());
        verifyNoInteractions(inspector);
    }

    @Test
    void returnsThePersistedRetryRelationshipWithoutCreatingAnotherTask() throws Exception {
        Fixture fixture = fixture();
        jdbc.update("UPDATE media_processing_task SET state='FAILED',retryable=true WHERE id=?", fixture.taskId());
        MediaProcessingTask retry = newTask(fixture.owner(), fixture.uploadId(), fixture.assetId());
        retry.setRetryOfTaskId(fixture.taskId()); tasks.insert(retry);
        var response = get(retry.getId(), fixture.token());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(fixture.taskId(), JsonPath.read(response.body(), "$.data.retryOfTaskId"));
        assertEquals("PENDING", JsonPath.read(response.body(), "$.data.state"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_task WHERE owner_id=?", Integer.class, fixture.owner()));
    }

    @Test
    void ownershipAndCaseSensitiveIdsAreEnforced() throws Exception {
        Fixture owner = fixture(), other = fixture();
        assertProblem(get(owner.taskId(), other.token()), 404, "RESOURCE_NOT_FOUND");
        assertProblem(get(owner.taskId().toUpperCase(Locale.ROOT), owner.token()), 404, "RESOURCE_NOT_FOUND");
        assertProblem(get("missing_task", owner.token()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, get(owner.taskId(), owner.token()).statusCode());
    }

    @Test
    void anonymousAndMalformedCredentialsReturnTheBearerChallenge() throws Exception {
        Fixture fixture = fixture();
        for (String token : Arrays.asList(null, "bad-token")) {
            var response = get(fixture.taskId(), token);
            assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidTaskIds")
    void invalidTaskIdsHaveAPathViolationAndDoNotQueryTaskStorage(String taskId) throws Exception {
        Fixture fixture = fixture(); clearInvocations(tasks);
        var response = get(taskId, fixture.token());
        assertProblem(response, 422, "VALIDATION_ERROR");
        assertEquals("path", JsonPath.read(response.body(), "$.violations[0].location"));
        assertEquals("taskId", JsonPath.read(response.body(), "$.violations[0].path"));
        verifyNoInteractions(tasks);
    }

    static Stream<String> invalidTaskIds() { return Stream.of("bad.task", "a".repeat(65)); }

    @ParameterizedTest
    @ValueSource(strings = {"auth_version=auth_version+1", "account_status='DISABLED'", "email_verified=0", "account_status='WRITE_BLOCKED'"})
    void rechecksTheAccountAfterBearerAuthentication(String change) throws Exception {
        Fixture fixture = fixture(); Map<String, Object> before = taskRow(fixture.taskId());
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET " + change + " WHERE id=?", fixture.owner()); return principal;
        }).when(authentication).authenticate(fixture.token());

        var response = get(fixture.taskId(), fixture.token());

        if (change.contains("WRITE_BLOCKED")) assertEquals(200, response.statusCode(), response.body());
        else if (change.startsWith("auth_version")) assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
        else assertProblem(response, 403, change.startsWith("email_verified") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertEquals(before, taskRow(fixture.taskId()));
    }

    @Test
    void readsAndNotFoundResultsConsumeTheIndependentQueryQuota() throws Exception {
        Fixture fixture = fixture(); Map<String, Object> before = taskRow(fixture.taskId());
        assertEquals(200, get(fixture.taskId(), fixture.token()).statusCode());
        assertProblem(get("missing_task", fixture.token()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, get(fixture.taskId(), fixture.token()).statusCode());
        var response = get(fixture.taskId(), fixture.token());
        assertProblem(response, 429, "RATE_LIMITED");
        long delay = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(delay >= 1 && delay <= 60);
        assertEquals(before, taskRow(fixture.taskId()));
    }

    @Test
    void databaseReadFailureReturns503WithoutExposingTheDatabaseError() throws Exception {
        Fixture fixture = fixture(); Map<String, Object> before = taskRow(fixture.taskId());
        doThrow(new TransientDataAccessResourceException("private SQL detail and disk path"))
                .when(tasks).findOwned(fixture.taskId(), fixture.owner());
        var response = get(fixture.taskId(), fixture.token());
        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("private SQL detail"));
        assertEquals(before, taskRow(fixture.taskId()));
    }

    @Test
    void anInternalCleanupTaskIdCannotBeReadThroughThePublicMediaTaskEndpoint() throws Exception {
        Fixture fixture = fixture(); String cleanupId = "cleanup_" + id();
        jdbc.update("""
                INSERT INTO media_upload_cleanup
                    (id,owner_id,upload_id,asset_id,storage_key,signer_id,state,attempt_count,available_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'FAILED',1,?,?,?)
                """, cleanupId, fixture.owner(), fixture.uploadId(), fixture.assetId(),
                "private/" + fixture.owner() + "/" + fixture.assetId() + "/source", "f".repeat(64), CREATED, CREATED, CREATED);
        assertProblem(get(cleanupId, fixture.token()), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void generatedResponsesDeclareTheContractHeaders() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Object> responses = JsonPath.read(response.body(), "$.paths['/api/v1/tasks/{taskId}'].get.responses");
        assertEquals(Set.of("200", "400", "401", "403", "404", "422", "429", "500", "503"), responses.keySet());
        for (String status : responses.keySet()) {
            Map<String, Object> headers = (Map<String, Object>) ((Map<?, ?>) responses.get(status)).get("headers");
            assertNotNull(headers, status + "应声明响应头");
            assertTrue(headers.keySet().containsAll(Set.of("X-Request-Id", "Cache-Control")), status + ":" + headers.keySet());
            if ("401".equals(status)) assertTrue(headers.containsKey("WWW-Authenticate"));
            if ("429".equals(status)) assertTrue(headers.containsKey("Retry-After"));
        }
    }

    private Fixture fixture() {
        UserAccount account = LoginTestSupport.account();
        account.setId(id()); account.setEmail("task-query-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail()); account.setAuthVersion(1L);
        owners.add(account.getId()); users.insert(account); roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        var asset = new MediaAsset();
        asset.setId(id()); asset.setOwnerId(account.getId()); asset.setScene("CONTENT_IMAGE"); asset.setFileName("task-query.png");
        asset.setDeclaredContentType("image/png"); asset.setDeclaredSizeBytes(68L);
        asset.setStorageKey("private/" + account.getId() + "/" + asset.getId() + "/source");
        asset.setState("PROCESSING"); asset.setCreatedAt(CREATED); assets.insert(asset);
        var upload = new MediaUpload();
        upload.setId(id()); upload.setAssetId(asset.getId()); upload.setOwnerId(account.getId()); upload.setStorageKey(asset.getStorageKey());
        upload.setContentType("image/png"); upload.setState("COMPLETED"); upload.setVersion(2); upload.setSignatureAt(CREATED);
        upload.setSignatureTtlSeconds(600); upload.setSignerId("f".repeat(64)); upload.setExpiresAt(CREATED.plusHours(2));
        upload.setCreatedAt(CREATED); uploads.insert(upload);
        MediaProcessingTask task = newTask(account.getId(), upload.getId(), asset.getId()); tasks.insert(task);
        return new Fixture(account.getId(), sessions.create(account.getId(), account.getAuthVersion(), "task-query-test").accessToken(),
                task.getId(), upload.getId(), asset.getId());
    }

    private MediaProcessingTask newTask(String owner, String uploadId, String assetId) {
        var task = new MediaProcessingTask();
        task.setId("task_" + id()); task.setOwnerId(owner); task.setUploadId(uploadId); task.setAssetId(assetId);
        task.setType("MEDIA_PROCESSING"); task.setState("PENDING"); task.setProgressPercent(0); task.setRetryable(false);
        task.setStorageKey("private/" + owner + "/" + assetId + "/source"); task.setSignerId("f".repeat(64));
        task.setObjectEtag("opaque-etag"); task.setObjectSizeBytes(68L); task.setObjectContentType("image/png");
        task.setObjectLastModified(CREATED); task.setCreatedAt(CREATED); task.setUpdatedAt(CREATED);
        return task;
    }

    private HttpResponse<String> get(String taskId, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks/" + taskId))
                .timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals(status, ((Number) JsonPath.read(response.body(), "$.status")).intValue());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.<String>read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private Map<String, Object> taskRow(String taskId) { return jdbc.queryForMap("SELECT * FROM media_processing_task WHERE id=?", taskId); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private record Fixture(String owner, String token, String taskId, String uploadId, String assetId) { }
}
