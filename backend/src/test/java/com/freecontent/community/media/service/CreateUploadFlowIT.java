package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.media.storage.UploadSigner;
import com.freecontent.community.media.config.StorageProperties;
import io.minio.*;
import com.freecontent.community.media.entity.MediaUpload;
import com.freecontent.community.media.mapper.MediaUploadMapper;
import com.freecontent.community.media.ratelimit.UploadCreationRateLimiter;
import com.freecontent.community.common.error.*;
import org.springframework.dao.TransientDataAccessResourceException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.email-challenge.delivery-enabled=false")
class CreateUploadFlowIT {
    private static final String PREFIX = "test:create-upload-it:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private UploadSigner signer;
    @Autowired private StorageProperties storage;
    @MockitoSpyBean private MediaUploadMapper uploadRows;
    @Autowired private UploadCreationRateLimiter limiter;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> uploadedObjects = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolateRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-creation.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!uploadedObjects.isEmpty()) {
            try (MinioClient minio = minio()) {
                for (String key : uploadedObjects) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
            }
        }
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : userIds) {
            // The first red run has no media tables yet.
            for (String table : List.of("media_upload_request", "media_upload_session", "media_asset")) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table) == 1) {
                    jdbc.update("DELETE FROM " + table + " WHERE owner_id=?", id);
                }
            }
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void createsPrivateSingleUploadWithTwoHourSessionAndTenMinuteSignedPut() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        Instant start = Instant.now();
        HttpResponse<String> response = create(token, UUID.randomUUID().toString(), body("CONTENT_IMAGE", 68));
        assertEquals(201, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "assetId", "strategy", "state", "version", "partSizeBytes", "totalParts", "uploadedParts", "singleUploadTarget", "expiresAt"), data.keySet());
        String uploadId = (String) data.get("id");
        assertEquals("/api/v1/media/uploads/" + uploadId, response.headers().firstValue("Location").orElseThrow());
        assertEquals("SINGLE", data.get("strategy"));
        assertEquals("UPLOADING", data.get("state"));
        assertEquals(1, data.get("version"));
        assertNull(data.get("partSizeBytes"));
        assertEquals(1, data.get("totalParts"));
        assertEquals(List.of(), data.get("uploadedParts"));
        Instant deadline = Instant.parse((String) data.get("expiresAt"));
        assertTrue(deadline.isAfter(start.plusSeconds(7190)) && deadline.isBefore(Instant.now().plusSeconds(7210)));
        Map<String, Object> target = JsonPath.read(response.body(), "$.data.singleUploadTarget");
        assertEquals("PUT", target.get("method"));
        assertEquals(Map.of("Content-Type", "image/png", "Content-Length", "68"), target.get("headers"));
        Instant signedDeadline = Instant.parse((String) target.get("expiresAt"));
        assertTrue(signedDeadline.isAfter(start.plusSeconds(590)) && signedDeadline.isBefore(Instant.now().plusSeconds(610)));
        assertTrue(URI.create((String) target.get("url")).getRawQuery().contains("X-Amz-Signature="));
        assertEquals("WAITING_UPLOAD", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=? AND owner_id=?", String.class, data.get("assetId"), user.getId()));
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=? AND owner_id=?", String.class, uploadId, user.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_request WHERE owner_id=? AND state='SUCCEEDED'", Integer.class, user.getId()));
        assertEquals(1, users.selectById(user.getId()).getVersion());
    }

    @Test
    void sameKeyAndFingerprintReplayTheOriginalUploadAndSignedTarget() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String key = UUID.randomUUID().toString();
        HttpResponse<String> first = create(token, key, body("AVATAR", 68));
        assertEquals(201, first.statusCode());
        HttpResponse<String> replay = create(token, key, body("AVATAR", 68));
        assertEquals(201, replay.statusCode());
        assertTrue(Objects.equals(JsonPath.read(first.body(), "$.data"), JsonPath.read(replay.body(), "$.data")), "Replay must preserve the original business result without logging signed URLs");
        assertEquals(first.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE owner_id=?", Integer.class, user.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, user.getId()));
    }

    @Test
    void duplicateWhileFirstRequestIsPreparingReturnsInProgressWithoutWaitingForItsResult() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.getAndSet(false)) { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); }
            return invocation.callRealMethod();
        }).when(signer).sign(any(MediaUpload.class), anyLong(), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> pending = executor.submit(() -> create(token, key, body("AVATAR", 68)));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            HttpResponse<String> concurrent = create(token, key, body("AVATAR", 68));
            assertEquals(409, concurrent.statusCode());
            assertEquals("REQUEST_IN_PROGRESS", JsonPath.read(concurrent.body(), "$.code"));
            assertTrue(Long.parseLong(concurrent.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        } finally {
            release.countDown();
            try { assertEquals(201, pending.get(15, TimeUnit.SECONDS).statusCode()); }
            finally { executor.shutdownNow(); }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, user.getId()));
    }

    @ParameterizedTest
    @CsvSource({"CONTENT_IMAGE,text/plain,68,415", "CONTENT_VIDEO,video/mp4,68,415",
            "AVATAR,image/png,10485761,413", "REPORT_ATTACHMENT,image/png,10485761,413"})
    void rejectsFilesOutsideP0ImagePolicyWithoutReservingBusinessRecords(String scene, String type, long size, int status) throws Exception {
        UserAccount user = fixture();
        Map<String, Object> request = new HashMap<>(body(scene, size));
        request.put("contentType", type);
        HttpResponse<String> response = create(token(user), UUID.randomUUID().toString(), request);
        assertEquals(status, response.statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE owner_id=?", Integer.class, user.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_request WHERE owner_id=?", Integer.class, user.getId()));
    }

    @Test
    void invalidIdempotencyKeyLengthsAreRejectedBeforeCreatingRecords() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        for (String key : List.of("short", "a".repeat(129))) {
            HttpResponse<String> response = create(token, key, body("AVATAR", 68));
            assertEquals(422, response.statusCode());
            assertEquals("VALIDATION_ERROR", JsonPath.read(response.body(), "$.code"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_request WHERE owner_id=?", Integer.class, user.getId()));
    }

    @Test
    void requestValuesKeepTheirDeclaredJsonTypesAndUnknownFieldsAreRejected() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (Map.Entry<String, Object> entry : Map.<String, Object>of("scene", 1, "fileName", 42, "contentType", 42,
                "sizeBytes", 1.5, "ownerId", "someone-else").entrySet()) {
            Map<String, Object> request = new HashMap<>(body("AVATAR", 68));
            request.put(entry.getKey(), entry.getValue()); invalid.add(request);
        }
        Map<String, Object> nullSha = new HashMap<>(body("AVATAR", 68));
        nullSha.put("sha256", null); invalid.add(nullSha);
        for (Map<String, Object> request : invalid) {
            HttpResponse<String> response = create(token, UUID.randomUUID().toString(), request);
            assertEquals(400, response.statusCode(), "Invalid field types must not be coerced");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE owner_id=?", Integer.class, user.getId()));
    }

    @Test
    void signedUrlActuallyUploadsToPrivateMinioAndBindsMethodObjectAndContentType() throws Exception {
        UserAccount user = fixture();
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AxoAAAAASUVORK5CYII=");
        HttpResponse<String> response = create(token(user), UUID.randomUUID().toString(), body("CONTENT_IMAGE", png.length));
        assertEquals(201, response.statusCode());
        String assetId = JsonPath.read(response.body(), "$.data.assetId");
        String key = jdbc.queryForObject("SELECT storage_key FROM media_asset WHERE id=? AND owner_id=?", String.class, assetId, user.getId());
        assertTrue(key.startsWith("private/" + user.getId() + "/"));
        uploadedObjects.add(key);
        String url = JsonPath.read(response.body(), "$.data.singleUploadTarget.url");
        assertEquals(403, binary(url, "GET", "image/png", null));
        assertEquals(403, binary(url, "PUT", "image/jpeg", png));
        assertEquals(403, binary(url.replace("/source?", "/wrong-object?"), "PUT", "image/png", png));
        assertEquals(200, binary(url, "PUT", "image/png", png));
        try (MinioClient minio = minio()) {
            var stat = minio.statObject(StatObjectArgs.builder().bucket(storage.bucket()).object(key).build());
            assertEquals(png.length, stat.size());
            assertEquals("image/png", stat.contentType());
        }
        int publicStatus = binary(url.substring(0, url.indexOf('?')), "GET", null, null);
        assertTrue(publicStatus == 403 || publicStatus == 404);
        assertEquals("WAITING_UPLOAD", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, assetId));
    }

    @Test
    void signedTargetRejectsWrongContentLengthAndBindsOptionalChecksum() throws Exception {
        UserAccount user = fixture();
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AxoAAAAASUVORK5CYII=");
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        Map<String, Object> request = new HashMap<>(body("CONTENT_IMAGE", png.length));
        request.put("sha256", sha256);
        HttpResponse<String> response = create(token(user), UUID.randomUUID().toString(), request);
        assertEquals(201, response.statusCode());

        String assetId = JsonPath.read(response.body(), "$.data.assetId");
        uploadedObjects.add(jdbc.queryForObject("SELECT storage_key FROM media_asset WHERE id=? AND owner_id=?",
                String.class, assetId, user.getId()));
        Map<String, Object> target = JsonPath.read(response.body(), "$.data.singleUploadTarget");
        @SuppressWarnings("unchecked") Map<String, Object> headers = (Map<String, Object>) target.get("headers");
        assertEquals(Long.toString(png.length), headers.get("Content-Length"));
        String checksum = (String) headers.get("x-amz-checksum-sha256");
        assertEquals(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(png)), checksum);
        String url = (String) target.get("url");

        int oversized = binary(url, "PUT", "image/png", new byte[png.length + 1], Map.of());
        assertTrue(oversized == 400 || oversized == 403, () -> "Unexpected length rejection status: " + oversized);
        assertEquals(200, binary(url, "PUT", "image/png", png, Map.of("x-amz-checksum-sha256", checksum)));

        String wrongChecksum = Base64.getEncoder().encodeToString(new byte[32]);
        HttpResponse<String> second = create(token(user), UUID.randomUUID().toString(), request);
        assertEquals(201, second.statusCode());
        String secondUrl = JsonPath.read(second.body(), "$.data.singleUploadTarget.url");
        int rejected = binary(secondUrl, "PUT", "image/png", png, Map.of("x-amz-checksum-sha256", wrongChecksum));
        assertTrue(rejected == 400 || rejected == 403, () -> "Unexpected checksum rejection status: " + rejected);
    }

    @Test
    void sameKeyWithChangedParametersConflictsAndAnotherUserHasAnIndependentScope() throws Exception {
        UserAccount first = fixture(), second = fixture();
        String key = UUID.randomUUID().toString();
        assertEquals(201, create(token(first), key, body("AVATAR", 68)).statusCode());
        HttpResponse<String> conflict = create(token(first), key, body("CONTENT_IMAGE", 68));
        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED", JsonPath.read(conflict.body(), "$.code"));
        assertEquals(201, create(token(second), key, body("CONTENT_IMAGE", 68)).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, first.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, second.getId()));
    }

    @Test
    void signingFailureReleasesReservationAndTheSameKeyCanRetry() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).doCallRealMethod().when(signer).sign(any(MediaUpload.class), anyLong(), any());
        assertEquals(503, create(token, key, body("AVATAR", 68)).statusCode());
        assertNoRecords(user);
        assertEquals(201, create(token, key, body("AVATAR", 68)).statusCode());
    }

    @Test
    void databaseFailureRollsBackAssetSessionAndSuccessResult() throws Exception {
        UserAccount user = fixture();
        doThrow(new TransientDataAccessResourceException("simulated media write failure")).when(uploadRows).insert(any(MediaUpload.class));
        assertEquals(503, create(token(user), UUID.randomUUID().toString(), body("AVATAR", 68)).statusCode());
        assertNoRecords(user);
    }

    @Test
    void credentialChangeBetweenAuthenticationAndCommitRejectsTheOldRequest() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        doAnswer(invocation -> {
            jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", user.getId());
            return invocation.callRealMethod();
        }).when(signer).sign(any(MediaUpload.class), anyLong(), any());
        HttpResponse<String> response = create(token, UUID.randomUUID().toString(), body("AVATAR", 68));
        assertEquals(401, response.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", JsonPath.read(response.body(), "$.code"));
        assertNoRecords(user);
    }

    @Test
    void replayDoesNotConsumeTheHourlyQuotaAndReturnsOriginalResult() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        for (int i=0; i<99; i++) limiter.acquire(user.getId(), "test-source-" + i);
        HttpResponse<String> first = create(token, key, body("AVATAR", 68));
        assertEquals(201, first.statusCode());
        HttpResponse<String> replay = create(token, key, body("AVATAR", 68));
        assertEquals(201, replay.statusCode());
        assertEquals((String) JsonPath.read(first.body(), "$.data.id"),
                (String) JsonPath.read(replay.body(), "$.data.id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, user.getId()));
    }

    @Test
    void expiredLeaseCanBeTakenOverAndLateOriginalRequestCannotOverwriteTheWinner() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.getAndSet(false)) { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)); }
            return invocation.callRealMethod();
        }).when(signer).sign(any(MediaUpload.class), anyLong(), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> pending = executor.submit(() -> create(token, key, body("AVATAR", 68)));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            jdbc.update("UPDATE media_upload_request SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE owner_id=?", user.getId());
            assertEquals(201, create(token, key, body("AVATAR", 68)).statusCode());
        } finally {
            release.countDown();
            try { assertEquals(409, pending.get(15, TimeUnit.SECONDS).statusCode()); }
            finally { executor.shutdownNow(); }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, user.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_request WHERE owner_id=? AND state='SUCCEEDED'", Integer.class, user.getId()));
    }

    @Test
    void expiredIdempotencyKeyCanCreateANewUploadWithoutRemovingTheOldResource() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        HttpResponse<String> first = create(token, key, body("AVATAR", 68));
        assertEquals(201, first.statusCode());
        jdbc.update("UPDATE media_upload_request SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE owner_id=?", user.getId());
        HttpResponse<String> next = create(token, key, body("AVATAR", 69));
        assertEquals(201, next.statusCode());
        assertNotEquals((String) JsonPath.read(first.body(), "$.data.id"), (String) JsonPath.read(next.body(), "$.data.id"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, user.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_request WHERE owner_id=?", Integer.class, user.getId()));
    }

    @Test
    void eightConcurrentIdenticalRequestsCreateOnlyOneBusinessResource() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> pending = new ArrayList<>();
        try {
            for (int i=0; i<8; i++) pending.add(executor.submit(() -> { start.await(); return create(token, key, body("AVATAR", 68)); }));
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<HttpResponse<String>> task : pending) {
                HttpResponse<String> result = task.get(15, TimeUnit.SECONDS);
                if (result.statusCode() == 201) ids.add(JsonPath.read(result.body(), "$.data.id"));
                else {
                    assertEquals(409, result.statusCode());
                    assertEquals("REQUEST_IN_PROGRESS", JsonPath.read(result.body(), "$.code"));
                }
            }
            assertEquals(1, ids.size());
        } finally { executor.shutdownNow(); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, user.getId()));
    }

    @Test
    void authenticationAccountRestrictionsAndRequiredHeaderAreEnforced() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        assertEquals(401, create(null, key, body("AVATAR", 68)).statusCode());
        assertEquals(400, create(token, null, body("AVATAR", 68)).statusCode());
        for (String status : List.of("WRITE_BLOCKED", "DISABLED")) {
            jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", status, user.getId());
            HttpResponse<String> response = create(token, key, body("AVATAR", 68));
            assertEquals(403, response.statusCode());
            assertEquals("ACCOUNT_RESTRICTED", JsonPath.read(response.body(), "$.code"));
        }
        jdbc.update("UPDATE app_user SET account_status='ACTIVE',email_verified=0 WHERE id=?", user.getId());
        HttpResponse<String> unverified = create(token, key, body("CONTENT_IMAGE", 68));
        assertEquals(403, unverified.statusCode());
        assertEquals("EMAIL_VERIFICATION_REQUIRED", JsonPath.read(unverified.body(), "$.code"));
        assertNoRecords(user);
    }

    @Test
    void generatedOpenApiPreservesCreationRequestAndResponseConstraints() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/media/uploads'].post");
        assertEquals("createUpload", operation.get("operationId"));
        assertTrue(((Map<?, ?>) operation.get("responses")).containsKey("201"));
        assertTrue(((List<?>) operation.get("security")).stream().anyMatch(value -> ((Map<?, ?>) value).containsKey("bearerAuth")));
        Map<?, ?> key = (Map<?, ?>) ((List<?>) operation.get("parameters")).stream().filter(value -> "Idempotency-Key".equals(((Map<?, ?>) value).get("name"))).findFirst().orElseThrow();
        assertEquals(true, key.get("required"));
        assertEquals(16, ((Map<?, ?>) key.get("schema")).get("minLength"));
        assertEquals(128, ((Map<?, ?>) key.get("schema")).get("maxLength"));
        Map<String, Object> input = JsonPath.read(response.body(), "$.components.schemas.CreateUploadRequest");
        assertEquals(false, input.get("additionalProperties"));
        assertEquals(Set.of("scene", "fileName", "contentType", "sizeBytes"), Set.copyOf((List<?>) input.get("required")));
        Map<?, ?> fields = (Map<?, ?>) input.get("properties");
        assertEquals(Set.of("scene", "fileName", "contentType", "sizeBytes", "sha256"), fields.keySet());
        assertEquals("integer", ((Map<?, ?>) fields.get("sizeBytes")).get("type"), () -> "Generated request schema: " + fields);
        Map<?, ?> output = JsonPath.read(response.body(), "$.components.schemas.UploadSession");
        assertEquals(10, ((List<?>) output.get("required")).size());
        Map<?, ?> id = (Map<?, ?>) ((Map<?, ?>) output.get("properties")).get("id");
        assertEquals(64, id.get("maxLength"));
        assertEquals("^[A-Za-z0-9_-]+$", id.get("pattern"));
    }

    private void assertNoRecords(UserAccount user) {
        for (String table : List.of("media_asset", "media_upload_session", "media_upload_request")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id=?", Integer.class, user.getId()));
        }
    }

    private MinioClient minio() {
        return MinioClient.builder().endpoint(storage.endpoint()).credentials(storage.accessKey(), storage.secretKey()).region(storage.region()).build();
    }

    private int binary(String url, String method, String type, byte[] data) throws Exception {
        return binary(url, method, type, data, Map.of());
    }

    private int binary(String url, String method, String type, byte[] data, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(data));
        if (type != null) request.header("Content-Type", type);
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private UserAccount fixture() {
        UserAccount user = LoginTestSupport.account();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail("create-upload-it-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        userIds.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }

    private String token(UserAccount user) {
        return sessions.create(user.getId(), user.getAuthVersion(), "upload-test").accessToken();
    }

    private Map<String, Object> body(String scene, long size) {
        return Map.of("scene", scene, "fileName", "diagram.png", "contentType", "image/png", "sizeBytes", size);
    }

    private HttpResponse<String> create(String token, String key, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/media/uploads"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (key != null) request.header("Idempotency-Key", key);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
