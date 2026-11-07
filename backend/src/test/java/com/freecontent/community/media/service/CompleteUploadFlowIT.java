package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.media.storage.UploadObjectCleaner;
import com.freecontent.community.media.storage.UploadObjectInspector;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.entity.MediaProcessingTask;
import com.freecontent.community.media.mapper.MediaProcessingTaskMapper;
import com.freecontent.community.media.ratelimit.UploadCompletionRateLimiter;
import com.freecontent.community.common.error.*;
import io.minio.*;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class CompleteUploadFlowIT {
    private static final String PREFIX = "test:complete-upload:" + UUID.randomUUID() + ":";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AxoAAAAASUVORK5CYII=");
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private UploadObjectCleaner cleaner;
    @Autowired private StorageProperties storage;
    @Autowired private UploadCompletionRateLimiter completionLimiter;
    @MockitoSpyBean private UploadObjectInspector inspector;
    @MockitoSpyBean private MediaProcessingTaskMapper tasks;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        for (String group : List.of("app.auth.login", "app.media.upload-creation", "app.media.upload-query",
                "app.media.upload-cancel", "app.media.upload-completion", "app.media.task-query", "app.media.asset-query")) {
            registry.add(group + ".redis-key-prefix", () -> PREFIX);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        for (String key : objectKeys) cleaner.deleteObject(key);
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : userIds) {
            for (String table : List.of("media_upload_completion_request", "media_processing_task", "media_upload_cleanup",
                    "media_upload_request", "media_upload_session", "media_asset")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                        Integer.class, table) == 1) jdbc.update("DELETE FROM " + table + " WHERE owner_id=?", owner);
            }
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test
    void completionCommitsTheUploadAssetAndDurableTaskBeforeReturning202() throws Exception {
        UserAccount owner = fixture();
        String token = token(owner);
        UploadFixture upload = create(token);
        put(upload.target(), PNG);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM media_upload_session WHERE id=?", upload.id());

        HttpResponse<String> result = complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}");

        assertEquals(202, result.statusCode(), result.body());
        String taskId = JsonPath.read(result.body(), "$.data.taskId");
        String statusUrl = "/api/v1/tasks/" + taskId;
        assertEquals(upload.assetId(), JsonPath.read(result.body(), "$.data.assetId"));
        assertEquals("PENDING", JsonPath.read(result.body(), "$.data.state"));
        assertEquals(statusUrl, JsonPath.read(result.body(), "$.data.statusUrl"));
        assertEquals(statusUrl, result.headers().firstValue("Location").orElseThrow());
        assertTrue(Long.parseLong(result.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, upload.id()));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, upload.id()));
        assertEquals("PROCESSING", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, upload.assetId()));
        Map<String, Object> task = jdbc.queryForMap("SELECT * FROM media_processing_task WHERE id=?", taskId);
        assertEquals(owner.getId(), task.get("owner_id"));
        assertEquals(upload.id(), task.get("upload_id"));
        assertEquals(upload.assetId(), task.get("asset_id"));
        assertEquals("PENDING", task.get("state"));
        assertEquals((long) PNG.length, ((Number) task.get("object_size_bytes")).longValue());
        assertFalse(((String) task.get("object_etag")).isBlank());
        assertFalse(result.body().contains("storageKey"));
        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM media_upload_session WHERE id=?", upload.id());
        for (String field : List.of("storage_key", "signature_at", "signature_ttl_seconds", "signer_id", "expires_at")) {
            assertEquals(before.get(field), after.get(field));
        }
    }

    @Test
    void receiptPointsToTheOwnersQueryablePendingTask() throws Exception {
        UserAccount owner = fixture();
        String token = token(owner);
        UploadFixture upload = create(token);
        put(upload.target(), PNG);
        var accepted = complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}");
        assertEquals(202, accepted.statusCode(), accepted.body());
        String path = JsonPath.read(accepted.body(), "$.data.statusUrl");

        var result = send("GET", path, token, null, null);

        assertEquals(200, result.statusCode(), result.body());
        String expectedTaskId = JsonPath.read(accepted.body(), "$.data.taskId");
        assertEquals(expectedTaskId, JsonPath.read(result.body(), "$.data.id"));
        assertEquals("MEDIA_PROCESSING", JsonPath.read(result.body(), "$.data.type"));
        assertEquals("PENDING", JsonPath.read(result.body(), "$.data.state"));
        assertEquals(0, (Integer) JsonPath.read(result.body(), "$.data.progressPercent"));
        assertEquals(upload.assetId(), JsonPath.read(result.body(), "$.data.assetId"));
        assertFalse((Boolean) JsonPath.read(result.body(), "$.data.retryable"));
        for (String field : List.of("errorCode", "errorMessage", "retryOfTaskId")) assertNull(JsonPath.read(result.body(), "$.data." + field));
        for (String field : List.of("createdAt", "updatedAt")) assertNotNull(JsonPath.read(result.body(), "$.data." + field));
        for (String secret : List.of("storageKey", "signerId", "objectEtag", "objectVersionId", "expectedSha256", "leaseToken")) {
            assertFalse(result.body().contains(secret));
        }
        assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void missingObjectReturns409WithoutConsumingTheKeyAndCanBeUploadedThenRetried() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture upload = create(token);
        var missing = complete(upload.id(), token, key, "{\"expectedVersion\":1}");
        assertProblem(missing, 409, "UPLOAD_STATE_CONFLICT");
        assertUnchanged(upload);
        put(upload.target(), PNG);
        assertEquals(202, complete(upload.id(), token, key, "{\"expectedVersion\":1}").statusCode());
    }

    @Test
    void actualSizeMismatchKeepsTheUploadRetryable() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture upload = create(token);
        replaceObject(upload, new byte[PNG.length + 1], "image/png");
        assertProblem(complete(upload.id(), token, key, "{\"expectedVersion\":1}"), 409, "UPLOAD_STATE_CONFLICT");
        assertUnchanged(upload);
        put(upload.target(), PNG);
        assertEquals(202, complete(upload.id(), token, key, "{\"expectedVersion\":1}").statusCode());
    }

    @Test
    void mismatchedStorageContentTypeIsNotAcceptedAsAValidatedImage() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token);
        replaceObject(upload, PNG, "image/jpeg");
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertUnchanged(upload);
    }

    @Test
    void replayReturnsTheOriginalReceiptWithoutCheckingStorageAgainOrCreatingAnotherTask() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture upload = create(token); put(upload.target(), PNG);
        var first = complete(upload.id(), token, key, "{\"expectedVersion\":1}");
        assertEquals(202, first.statusCode());
        String taskId = JsonPath.read(first.body(), "$.data.taskId");
        jdbc.update("UPDATE media_processing_task SET state='RUNNING',progress_percent=10 WHERE id=?", taskId);
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(inspector).inspect(anyString());
        var replay = complete(upload.id(), token, key, "{ \"expectedVersion\" : 1 }");
        assertEquals(202, replay.statusCode(), replay.body());
        Map<String, Object> originalData = JsonPath.read(first.body(), "$.data"), replayData = JsonPath.read(replay.body(), "$.data");
        assertEquals(originalData, replayData);
        assertEquals(first.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_task WHERE upload_id=?", Integer.class, upload.id()));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, upload.id()));
    }

    @Test
    void keyScopeIncludesTheUploadAndBodyAndNewKeysCannotCompleteAnAlreadyCompletedUpload() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture first = create(token), second = create(token); put(first.target(), PNG);
        assertEquals(202, complete(first.id(), token, key, "{\"expectedVersion\":1}").statusCode());
        assertProblem(complete(first.id(), token, key, "{\"expectedVersion\":2}"), 409, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(complete(second.id(), token, key, "{\"expectedVersion\":1}"), 409, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(complete(first.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":2}"), 409, "UPLOAD_STATE_CONFLICT");
        assertUnchanged(second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "EXPIRED"})
    void terminalStatesDoNotCreateAProcessingTask(String state) throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token);
        jdbc.update("UPDATE media_upload_session SET state=? WHERE id=?", state, upload.id());
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 409, "UPLOAD_STATE_CONFLICT");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_task WHERE upload_id=?", Integer.class, upload.id()));
        verifyNoInteractions(inspector);
    }

    @Test
    void expiredDeadlineStaleVersionAndVersionOverflowAreRejectedBeforeStorageIO() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token);
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":2}"), 409, "UPLOAD_STATE_CONFLICT");
        jdbc.update("UPDATE media_upload_session SET version=2147483647 WHERE id=?", upload.id());
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":2147483647}"), 409, "UPLOAD_STATE_CONFLICT");
        jdbc.update("UPDATE media_upload_session SET version=1,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", upload.id());
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 409, "UPLOAD_STATE_CONFLICT");
        assertUnchanged(upload); verifyNoInteractions(inspector);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "READY", "FAILED"})
    void anAssetAlreadyOutsideWaitingUploadCannotBeCompleted(String state) throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token);
        jdbc.update("UPDATE media_asset SET state=? WHERE id=?", state, upload.assetId());
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 409, "UPLOAD_STATE_CONFLICT");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_task WHERE upload_id=?", Integer.class, upload.id()));
    }

    @Test
    void singleUploadsRejectPartsIncludingExplicitNull() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token);
        for (String parts : List.of("null", "[]", "[{\"partNumber\":1,\"etag\":\"opaque\"}]")) {
            assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(),
                    "{\"expectedVersion\":1,\"parts\":" + parts + "}"), 422, "VALIDATION_ERROR");
        }
        assertUnchanged(upload);
    }

    @Test
    void taskInsertionFailureRollsBackBothStatesAndDoesNotKeepASuccessfulIdempotencyResult() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token); put(upload.target(), PNG);
        doThrow(new TransientDataAccessResourceException("simulated task insertion failure")).when(tasks).insert(any(MediaProcessingTask.class));
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 503, "DEPENDENCY_UNAVAILABLE");
        assertUnchanged(upload);
    }

    @Test
    void storageFailureReturns503AndPreservesTheSession() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token); put(upload.target(), PNG);
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(inspector).inspect(anyString());
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 503, "DEPENDENCY_UNAVAILABLE");
        assertUnchanged(upload);
    }

    @Test
    void ownershipRestrictionsAndTaskVisibilityAreEnforced() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), otherToken = token(fixture());
        UploadFixture upload = create(token); put(upload.target(), PNG);
        assertProblem(complete(upload.id(), otherToken, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 404, "RESOURCE_NOT_FOUND");
        assertProblem(complete("unknown-upload", token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 404, "RESOURCE_NOT_FOUND");
        assertProblem(complete(upload.id(), null, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 401, "AUTHENTICATION_REQUIRED");
        var first = complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}");
        assertEquals(202, first.statusCode()); String path = JsonPath.read(first.body(), "$.data.statusUrl");
        assertProblem(send("GET", path, otherToken, null, null), 404, "RESOURCE_NOT_FOUND");
        assertProblem(send("GET", path, null, null, null), 401, "AUTHENTICATION_REQUIRED");
        assertProblem(send("GET", "/api/v1/tasks/unknown", token, null, null), 404, "RESOURCE_NOT_FOUND");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", owner.getId());
        assertEquals(200, send("GET", path, token, null, null).statusCode());
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 403, "ACCOUNT_RESTRICTED");
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", owner.getId());
        assertProblem(send("GET", path, token, null, null), 403, "ACCOUNT_RESTRICTED");
    }

    @Test
    void cancellationCanCommitWhileTheStorageCheckIsPausedAndCompletionThenLoses() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token); put(upload.target(), PNG);
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        pauseFirstInspection(entered, proceed);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(204, send("DELETE", "/api/v1/media/uploads/" + upload.id() + "?expectedVersion=1", token, null, null).statusCode());
            proceed.countDown();
            assertProblem(pending.get(15, TimeUnit.SECONDS), 409, "UPLOAD_STATE_CONFLICT");
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, upload.id()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_task WHERE upload_id=?", Integer.class, upload.id()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_cleanup WHERE upload_id=?", Integer.class, upload.id()));
        } finally {
            proceed.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void inFlightReplayReturnsRequestInProgressAndLaterReplaysTheCommittedTask() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture upload = create(token); put(upload.target(), PNG);
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        pauseFirstInspection(entered, proceed);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> complete(upload.id(), token, key, "{\"expectedVersion\":1}"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var busy = complete(upload.id(), token, key, "{\"expectedVersion\":1}");
            assertProblem(busy, 409, "REQUEST_IN_PROGRESS");
            assertTrue(Long.parseLong(busy.headers().firstValue("Retry-After").orElseThrow()) >= 1);
            proceed.countDown();
            var first = pending.get(15, TimeUnit.SECONDS);
            assertEquals(202, first.statusCode(), first.body());
            var replay = complete(upload.id(), token, key, "{\"expectedVersion\":1}");
            assertEquals(202, replay.statusCode());
            String firstTask = JsonPath.read(first.body(), "$.data.taskId");
            assertEquals(firstTask, JsonPath.read(replay.body(), "$.data.taskId"));
            verify(inspector, times(1)).inspect(anyString());
        } finally {
            proceed.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void anExpiredLeaseCanBeReclaimedAndTheOldOwnerCannotOverwriteOrDeleteTheNewResult() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture upload = create(token); put(upload.target(), PNG);
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        pauseFirstInspection(entered, proceed);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldOwner = executor.submit(() -> complete(upload.id(), token, key, "{\"expectedVersion\":1}"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            jdbc.update("UPDATE media_upload_completion_request SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE upload_id=?", upload.id());
            var recovered = complete(upload.id(), token, key, "{\"expectedVersion\":1}");
            assertEquals(202, recovered.statusCode(), recovered.body());
            proceed.countDown();
            assertProblem(oldOwner.get(15, TimeUnit.SECONDS), 409, "REQUEST_IN_PROGRESS");
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM media_upload_completion_request WHERE upload_id=?", String.class, upload.id()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_task WHERE upload_id=?", Integer.class, upload.id()));
            String recoveredId = JsonPath.read(recovered.body(), "$.data.taskId");
            var replay = complete(upload.id(), token, key, "{\"expectedVersion\":1}");
            assertEquals(202, replay.statusCode()); assertEquals(recoveredId, JsonPath.read(replay.body(), "$.data.taskId"));
        } finally {
            proceed.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentCompletionRequestsCreateExactlyOneTask(boolean sameKey) throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture upload = create(token); put(upload.target(), PNG);
        var executor = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) pending.add(executor.submit(() -> {
                start.await(); return complete(upload.id(), token, sameKey ? key : UUID.randomUUID().toString(), "{\"expectedVersion\":1}");
            }));
            start.countDown(); Set<String> taskIds = new HashSet<>(); int accepted = 0;
            for (var request : pending) {
                var response = request.get(20, TimeUnit.SECONDS);
                if (response.statusCode() == 202) { accepted++; taskIds.add(JsonPath.read(response.body(), "$.data.taskId")); }
                else assertEquals(409, response.statusCode(), response.body());
            }
            assertTrue(accepted >= 1); if (!sameKey) assertEquals(1, accepted);
            assertEquals(1, taskIds.size());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_task WHERE upload_id=?", Integer.class, upload.id()));
            assertEquals(2, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, upload.id()));
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test
    void successfulReplaysConsumeCompletionQuotaButDoNotConsumeOtherUploadOrTaskQueryQuotas() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture upload = create(token); put(upload.target(), PNG);
        var first = complete(upload.id(), token, key, "{\"expectedVersion\":1}"); assertEquals(202, first.statusCode());
        for (int i = 1; i < 60; i++) completionLimiter.acquire(owner.getId(), "127.0.0.1");
        assertProblem(complete(upload.id(), token, key, "{\"expectedVersion\":1}"), 429, "RATE_LIMITED");
        String path = JsonPath.read(first.body(), "$.data.statusUrl");
        assertEquals(200, send("GET", path, token, null, null).statusCode());
        var view = send("GET", "/api/v1/media/uploads/" + upload.id(), token, null, null);
        assertEquals(200, view.statusCode()); assertNull(JsonPath.read(view.body(), "$.data.singleUploadTarget"));
        UploadFixture another = create(token);
        assertEquals(204, send("DELETE", "/api/v1/media/uploads/" + another.id() + "?expectedVersion=1", token, null, null).statusCode());
    }

    @Test
    void creationAndCompletionUseIndependentIdempotencyNamespaces() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), key = UUID.randomUUID().toString();
        UploadFixture upload = create(token, key); put(upload.target(), PNG);
        assertEquals(202, complete(upload.id(), token, key, "{\"expectedVersion\":1}").statusCode());
        assertProblem(send("DELETE", "/api/v1/media/uploads/" + upload.id() + "?expectedVersion=1", token, null, null), 409, "UPLOAD_STATE_CONFLICT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth_version=auth_version+1", "account_status='WRITE_BLOCKED'", "email_verified=0"})
    void authorizationIsRecheckedAfterStorageIO(String change) throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token); put(upload.target(), PNG);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET " + change + " WHERE id=?", owner.getId()); return result;
        }).when(inspector).inspect(anyString());
        String code = change.startsWith("auth_version") ? "AUTHENTICATION_REQUIRED"
                : change.startsWith("email_verified") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED";
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"),
                change.startsWith("auth_version") ? 401 : 403, code);
        assertUnchanged(upload);
    }

    @Test
    void deadlineIsRecheckedAfterStorageIO() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token); put(upload.target(), PNG);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            jdbc.update("UPDATE media_upload_session SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", upload.id()); return result;
        }).when(inspector).inspect(anyString());
        assertProblem(complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}"), 409, "UPLOAD_STATE_CONFLICT");
        assertUnchanged(upload);
    }

    @Test
    void generatedOpenApiDeclaresCompletionAndTaskQueryWithTheirContractSchemas() throws Exception {
        var response = send("GET", "/v3/api-docs", null, null, null);
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/media/uploads/{uploadId}/completion'].post");
        assertEquals("completeMyUpload", operation.get("operationId"));
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertTrue(responses.keySet().containsAll(Set.of("202", "400", "401", "403", "404", "409", "413", "415", "422", "429", "500", "503")), responses.keySet().toString());
        Map<String, Object> headers = (Map<String, Object>) ((Map<?, ?>) responses.get("202")).get("headers");
        assertNotNull(headers); assertTrue(headers.keySet().containsAll(Set.of("Location", "Retry-After")));
        Map<String, Object> parts = JsonPath.read(response.body(), "$.components.schemas.CompleteUploadRequest.properties.parts");
        assertEquals("array", parts.get("type")); assertEquals(1, parts.get("minItems")); assertEquals(10000, parts.get("maxItems"));
        Map<String, Object> schema = JsonPath.read(response.body(), "$.components.schemas.CompleteUploadRequest");
        assertEquals(List.of("expectedVersion"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
        assertFalse(((Map<?, ?>) schema.get("properties")).containsKey("partsProvided"));
        Map<String, Object> taskQuery = JsonPath.read(response.body(), "$.paths['/api/v1/tasks/{taskId}'].get");
        assertEquals("getMyTask", taskQuery.get("operationId")); assertFalse(taskQuery.containsKey("requestBody"));
        assertTrue(((Map<?, ?>) taskQuery.get("responses")).containsKey("200"));
        List<String> receiptFields = JsonPath.read(response.body(), "$.components.schemas.TaskReceipt.required");
        assertTrue(receiptFields.containsAll(List.of("taskId", "assetId", "state", "statusUrl")));
    }

    @Test
    void assetQueryReflectsRealCreationAndCompletionWithoutClaimingTheFileIsReady() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); UploadFixture upload = create(token);
        var waiting = send("GET", "/api/v1/media/assets/" + upload.assetId(), token, null, null);
        assertEquals(200, waiting.statusCode(), waiting.body());
        assertEquals("WAITING_UPLOAD", JsonPath.read(waiting.body(), "$.data.state"));
        assertEquals("image/png", JsonPath.read(waiting.body(), "$.data.contentType"));
        assertEquals(0L, ((Number) JsonPath.read(waiting.body(), "$.data.sizeBytes")).longValue());
        put(upload.target(), PNG);
        var accepted = complete(upload.id(), token, UUID.randomUUID().toString(), "{\"expectedVersion\":1}");
        assertEquals(202, accepted.statusCode(), accepted.body());
        clearInvocations(inspector);
        var processing = send("GET", "/api/v1/media/assets/" + upload.assetId(), token, null, null);
        assertEquals(200, processing.statusCode(), processing.body());
        assertEquals("PROCESSING", JsonPath.read(processing.body(), "$.data.state"));
        assertEquals(0L, ((Number) JsonPath.read(processing.body(), "$.data.sizeBytes")).longValue());
        assertNull(JsonPath.read(processing.body(), "$.data.width"));
        assertNull(jdbc.queryForObject("SELECT actual_content_type FROM media_asset WHERE id=?", String.class, upload.assetId()));
        verifyNoInteractions(inspector);
    }

    private void pauseFirstInspection(CountDownLatch entered, CountDownLatch proceed) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!proceed.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("storage test did not resume");
            }
            return result;
        }).when(inspector).inspect(anyString());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    private void assertUnchanged(UploadFixture upload) {
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, upload.id()));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM media_upload_session WHERE id=?", Integer.class, upload.id()));
        assertEquals("WAITING_UPLOAD", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, upload.assetId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_processing_task WHERE upload_id=?", Integer.class, upload.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_completion_request WHERE upload_id=?", Integer.class, upload.id()));
    }

    private void replaceObject(UploadFixture upload, byte[] data, String contentType) throws Exception {
        try (var minio = MinioClient.builder().endpoint(storage.endpoint()).region(storage.region())
                .credentials(storage.accessKey(), storage.secretKey()).build()) {
            String key = jdbc.queryForObject("SELECT storage_key FROM media_upload_session WHERE id=?", String.class, upload.id());
            minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(key).contentType(contentType)
                    .stream(new ByteArrayInputStream(data), (long) data.length, -1L).build());
        }
    }

    private UserAccount fixture() {
        UserAccount owner = LoginTestSupport.account();
        owner.setId(UUID.randomUUID().toString().replace("-", ""));
        owner.setEmail("completion-" + owner.getId() + "@example.test");
        owner.setEmailNormalized(owner.getEmail());
        userIds.add(owner.getId()); users.insert(owner); roles.insertDefaultRole(owner.getId(), owner.getCreatedAt());
        return owner;
    }

    private String token(UserAccount owner) { return sessions.create(owner.getId(), owner.getAuthVersion(), "completion-test").accessToken(); }

    private UploadFixture create(String token) throws Exception {
        return create(token, UUID.randomUUID().toString());
    }

    private UploadFixture create(String token, String key) throws Exception {
        var result = send("POST", "/api/v1/media/uploads", token, key, json.writeValueAsString(
                Map.of("scene", "CONTENT_IMAGE", "fileName", "image.png", "contentType", "image/png", "sizeBytes", PNG.length)));
        assertEquals(201, result.statusCode(), result.body());
        String id = JsonPath.read(result.body(), "$.data.id"), assetId = JsonPath.read(result.body(), "$.data.assetId");
        objectKeys.add(jdbc.queryForObject("SELECT storage_key FROM media_upload_session WHERE id=?", String.class, id));
        return new UploadFixture(id, assetId, JsonPath.read(result.body(), "$.data.singleUploadTarget"));
    }

    private void put(Map<String, Object> target, byte[] data) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create((String) target.get("url"))).timeout(Duration.ofSeconds(10));
        ((Map<String, String>) target.get("headers")).forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Content-Length")) builder.header(name, value);
        });
        var response = client.send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private HttpResponse<String> complete(String id, String token, String key, String body) throws Exception {
        return send("POST", "/api/v1/media/uploads/" + id + "/completion", token, key, body);
    }

    private HttpResponse<String> send(String method, String path, String token, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record UploadFixture(String id, String assetId, Map<String, Object> target) { }
}
