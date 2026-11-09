package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.entity.MediaUpload;
import com.freecontent.community.media.storage.UploadSigner;
import com.freecontent.community.media.mapper.MediaUploadMapper;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.ratelimit.UploadQueryRateLimiter;
import io.minio.*;
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
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.email-challenge.delivery-enabled=false")
class GetMyUploadFlowIT {
    private static final String PREFIX = "test:get-upload-it:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private MediaUploadMapper uploads;
    @Autowired private StorageProperties storage;
    @Autowired private UploadQueryRateLimiter queryLimiter;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private UploadSigner signer;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!objectKeys.isEmpty()) {
            try (MinioClient minio = minio()) {
                for (String key : objectKeys) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
            }
        }
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : userIds) {
            for (String table : List.of("media_upload_request", "media_upload_session", "media_asset")) {
                jdbc.update("DELETE FROM " + table + " WHERE owner_id=?", id);
            }
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void renewsExpiredSignatureWithoutChangingSessionOrOriginalCreateReceipt() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        HttpResponse<String> created = create(token, key);
        assertEquals(201, created.statusCode());
        String id = JsonPath.read(created.body(), "$.data.id");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("UPDATE media_upload_session SET signature_at=?,expires_at=?,version=7 WHERE id=?",
                utc(now.minusSeconds(1200)), utc(now.plusSeconds(3600)), id);
        Map<String, Object> snapshot = snapshot(id);
        HttpResponse<String> oldReceipt = create(token, key);

        HttpResponse<String> response = get(id, token);

        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        Map<String, Object> root = JsonPath.read(response.body(), "$");
        assertEquals(Set.of("data", "requestId", "serverTime"), root.keySet());
        assertEquals(root.get("requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertNotNull(Instant.parse((String) root.get("serverTime")));
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "assetId", "strategy", "state", "version", "partSizeBytes", "totalParts", "uploadedParts", "singleUploadTarget", "expiresAt"), data.keySet());
        assertEquals(id, data.get("id"));
        assertEquals(JsonPath.read(created.body(), "$.data.assetId"), data.get("assetId"));
        assertEquals("SINGLE", data.get("strategy"));
        assertEquals("UPLOADING", data.get("state"));
        assertEquals(7, data.get("version"));
        assertNull(data.get("partSizeBytes"));
        assertEquals(1, data.get("totalParts"));
        assertEquals(List.of(), data.get("uploadedParts"));
        assertEquals(now.plusSeconds(3600), Instant.parse((String) data.get("expiresAt")));
        Instant signedExpiry = Instant.parse(JsonPath.read(response.body(), "$.data.singleUploadTarget.expiresAt"));
        assertTrue(signedExpiry.isAfter(now.plusSeconds(590)) && signedExpiry.isBefore(Instant.now().plusSeconds(610)));
        String beforeUrl = JsonPath.read(oldReceipt.body(), "$.data.singleUploadTarget.url");
        String renewedUrl = JsonPath.read(response.body(), "$.data.singleUploadTarget.url");
        assertFalse(Objects.equals(beforeUrl, renewedUrl), "The signature should be renewed without logging credentials");
        assertEquals(URI.create(beforeUrl).getRawPath(), URI.create(renewedUrl).getRawPath());
        assertEquals(snapshot, snapshot(id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_upload_session WHERE owner_id=?", Integer.class, user.getId()));
        HttpResponse<String> replay = create(token, key);
        assertTrue(Objects.equals(JsonPath.read(oldReceipt.body(), "$.data"), JsonPath.read(replay.body(), "$.data")), "Query must not alter the creation receipt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "EXPIRED"})
    void terminalStatesReturnMetadataWithoutIssuingAnotherUploadTarget(String state) throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        jdbc.update("UPDATE media_upload_session SET state=?,version=8,expires_at=? WHERE id=?", state, utc(Instant.now().minusSeconds(1)), id);
        Map<String, Object> before = snapshot(id);
        HttpResponse<String> response = get(id, token);
        assertEquals(200, response.statusCode());
        assertEquals(state, JsonPath.read(response.body(), "$.data.state"));
        assertEquals(8, (Integer) JsonPath.read(response.body(), "$.data.version"));
        assertTrue(JsonPath.read(response.body(), "$.data.singleUploadTarget") == null, "Terminal states cannot issue upload credentials");
        assertEquals(before, snapshot(id));
    }

    @Test
    void elapsedUploadingSessionIsReportedExpiredWithoutUpdatingTheDatabase() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        jdbc.update("UPDATE media_upload_session SET expires_at=? WHERE id=?", utc(Instant.now().minusSeconds(1)), id);
        Map<String, Object> before = snapshot(id);
        HttpResponse<String> response = get(id, token);
        assertEquals(200, response.statusCode());
        assertEquals("EXPIRED", JsonPath.read(response.body(), "$.data.state"));
        assertTrue(JsonPath.read(response.body(), "$.data.singleUploadTarget") == null, "Expired sessions cannot issue upload credentials");
        assertEquals(before, snapshot(id));
    }

    @Test
    void writeBlockedAccountCanReadMetadataButCannotReceiveAnUploadCredential() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", user.getId());
        HttpResponse<String> response = get(id, token);
        assertEquals(200, response.statusCode());
        assertEquals("UPLOADING", JsonPath.read(response.body(), "$.data.state"));
        assertTrue(JsonPath.read(response.body(), "$.data.singleUploadTarget") == null, "A write restriction must not be bypassed through GET");
    }

    @Test
    void renewedSignatureIsClampedToTheOriginalSessionDeadline() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        Instant deadline = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(45);
        jdbc.update("UPDATE media_upload_session SET expires_at=? WHERE id=?", utc(deadline), id);
        HttpResponse<String> response = get(id, token);
        assertEquals(200, response.statusCode());
        Instant signedDeadline = Instant.parse(JsonPath.read(response.body(), "$.data.singleUploadTarget.expiresAt"));
        assertEquals(deadline, signedDeadline);
        assertEquals(deadline, Instant.parse(JsonPath.read(response.body(), "$.data.expiresAt")));
    }

    @Test
    void invalidUploadIdHasAPathViolationRatherThanQueryingTheDatabase() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        for (String id : List.of("invalid.id", "a".repeat(65))) {
            HttpResponse<String> response = get(id, token);
            assertEquals(422, response.statusCode());
            assertEquals("VALIDATION_ERROR", JsonPath.read(response.body(), "$.code"));
            assertEquals("path", JsonPath.read(response.body(), "$.violations[0].location"));
            assertEquals("uploadId", JsonPath.read(response.body(), "$.violations[0].path"));
        }
        verify(uploads, never()).findOwnedForShare(anyString(), anyString());
    }

    @Test
    void databaseFailureIs503AndDoesNotExposeAnUploadTarget() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        doThrow(new TransientDataAccessResourceException("simulated unavailable upload database")).when(uploads).findOwnedForShare(anyString(), anyString());
        HttpResponse<String> response = get(id, token);
        assertEquals(503, response.statusCode());
        assertEquals("DEPENDENCY_UNAVAILABLE", JsonPath.read(response.body(), "$.code"));
        assertTrue(!response.body().contains("singleUploadTarget"));
    }

    @Test
    void anonymousUnknownAndOtherUsersCannotReadOrRenewTheUpload() throws Exception {
        UserAccount owner = fixture(), other = fixture();
        String ownerToken = token(owner);
        String id = JsonPath.read(create(ownerToken, UUID.randomUUID().toString()).body(), "$.data.id");
        assertEquals(401, get(id, null).statusCode());
        assertEquals(401, get(id, "invalid-token").statusCode());
        assertEquals(404, get(id, token(other)).statusCode());
        assertEquals(404, get("not-existing", ownerToken).statusCode());
    }

    @Test
    void queryQuotaReturns429WithoutConsumingCreationQuota() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        for (int i=0; i<59; i++) queryLimiter.acquire(user.getId(), "test-source-" + i);
        assertEquals(200, get(id, token).statusCode());
        HttpResponse<String> limited = get(id, token);
        assertEquals(429, limited.statusCode());
        assertEquals("RATE_LIMITED", JsonPath.read(limited.body(), "$.code"));
        long retry = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retry >= 1 && retry <= 60);
        assertEquals(201, create(token, UUID.randomUUID().toString()).statusCode());
    }

    @Test
    void renewedTargetUploadsTheOriginalObjectWithItsLengthTypeAndChecksum() throws Exception {
        UserAccount user = fixture();
        String token = token(user), key = UUID.randomUUID().toString();
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AxoAAAAASUVORK5CYII=");
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(png);
        Map<String, Object> body = Map.of("scene", "CONTENT_IMAGE", "fileName", "original.png", "contentType", "image/png",
                "sizeBytes", png.length, "sha256", HexFormat.of().formatHex(checksum));
        HttpResponse<String> created = create(token, key, body);
        assertEquals(201, created.statusCode());
        String id = JsonPath.read(created.body(), "$.data.id");
        jdbc.update("UPDATE media_upload_session SET signature_at=? WHERE id=?", utc(Instant.now().minusSeconds(1200).truncatedTo(ChronoUnit.SECONDS)), id);
        Map<String, Object> before = snapshot(id);
        String objectKey = (String) before.get("storage_key");
        assertTrue(objectKey.startsWith("private/" + user.getId() + "/"));
        objectKeys.add(objectKey);
        Map<String, Object> expiredTarget = JsonPath.read(create(token, key, body).body(), "$.data.singleUploadTarget");
        assertEquals(403, put(expiredTarget, png));
        HttpResponse<String> query = get(id, token);
        assertEquals(200, query.statusCode());
        Map<String, Object> renewed = JsonPath.read(query.body(), "$.data.singleUploadTarget");
        Map<String, String> headers = JsonPath.read(query.body(), "$.data.singleUploadTarget.headers");
        assertEquals(Integer.toString(png.length), headers.get("Content-Length"));
        assertEquals(Base64.getEncoder().encodeToString(checksum), headers.get("x-amz-checksum-sha256"));
        assertEquals(200, put(renewed, png));
        try (MinioClient minio = minio()) {
            var stat = minio.statObject(StatObjectArgs.builder().bucket(storage.bucket()).object(objectKey).build());
            assertEquals(png.length, stat.size());
            assertEquals("image/png", stat.contentType());
        }
        assertEquals(before, snapshot(id));
        assertEquals("WAITING_UPLOAD", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, before.get("asset_id")));
    }

    @Test
    void credentialChangeAfterAuthenticationCannotIssueANewUploadCredential() throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", user.getId());
            return principal;
        }).when(authentication).authenticate(token);
        HttpResponse<String> response = get(id, token);
        assertEquals(401, response.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", JsonPath.read(response.body(), "$.code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_AUTHENTICATION", "AFTER_AUTHENTICATION"})
    void unverifiedEmailCannotRenewUploadAndUsesTheContractCode(String timing) throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        Map<String, Object> before = snapshot(id);
        clearInvocations(signer);
        if ("BEFORE_AUTHENTICATION".equals(timing)) {
            jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", user.getId());
        } else {
            doAnswer(invocation -> {
                SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
                jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", user.getId());
                return principal;
            }).when(authentication).authenticate(token);
        }

        HttpResponse<String> response = get(id, token);

        assertEquals(403, response.statusCode());
        assertEquals("PERMISSION_DENIED", JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(before, snapshot(id));
        assertFalse(response.body().contains("singleUploadTarget"));
        verifyNoInteractions(signer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRITE_BLOCKED", "DISABLED"})
    void restrictionChangedAfterAuthenticationIsRecheckedBeforeSigning(String status) throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", status, user.getId());
            return principal;
        }).when(authentication).authenticate(token);
        HttpResponse<String> response = get(id, token);
        if ("WRITE_BLOCKED".equals(status)) {
            assertEquals(200, response.statusCode());
            assertTrue(JsonPath.read(response.body(), "$.data.singleUploadTarget") == null, "Restriction must not be bypassed");
        } else {
            assertEquals(403, response.statusCode());
            assertEquals("ACCOUNT_RESTRICTED", JsonPath.read(response.body(), "$.code"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT", "SESSION"})
    void sharedReadLocksKeepSigningConsistentWithConcurrentRestrictionOrCancellation(String resource) throws Exception {
        UserAccount user = fixture();
        String token = token(user);
        String id = JsonPath.read(create(token, UUID.randomUUID().toString()).body(), "$.data.id");
        CountDownLatch signing = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            signing.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(signer).sign(any(MediaUpload.class), anyLong(), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<HttpResponse<String>> reading = executor.submit(() -> get(id, token));
        Future<Integer> writing = null;
        try {
            assertTrue(signing.await(5, TimeUnit.SECONDS));
            writing = executor.submit(() -> "ACCOUNT".equals(resource)
                    ? jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", user.getId())
                    : jdbc.update("UPDATE media_upload_session SET state='CANCELLED',version=version+1 WHERE id=?", id));
            Future<Integer> pending = writing;
            assertThrows(TimeoutException.class, () -> pending.get(150, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            try {
                assertEquals(200, reading.get(15, TimeUnit.SECONDS).statusCode());
                if (writing != null) assertEquals(1, writing.get(15, TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
        }
        HttpResponse<String> after = get(id, token);
        assertEquals(200, after.statusCode());
        assertTrue(JsonPath.read(after.body(), "$.data.singleUploadTarget") == null, "Subsequent reads must not issue another credential");
    }

    @Test
    void generatedOpenApiDeclaresTheOwnedQueryAndNullableUploadTarget() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/media/uploads/{uploadId}'].get");
        assertEquals("getMyUpload", operation.get("operationId"));
        assertFalse(operation.containsKey("requestBody"));
        assertTrue(((Map<?, ?>) operation.get("responses")).containsKey("200"));
        assertTrue(((List<?>) operation.get("security")).stream().anyMatch(value -> ((Map<?, ?>) value).containsKey("bearerAuth")));
        List<?> parameters = (List<?>) operation.get("parameters");
        assertEquals(1, parameters.size());
        Map<?, ?> parameter = (Map<?, ?>) parameters.get(0);
        assertEquals("uploadId", parameter.get("name"));
        assertEquals("path", parameter.get("in"));
        assertEquals(true, parameter.get("required"));
        Map<?, ?> schema = (Map<?, ?>) parameter.get("schema");
        assertEquals(1, schema.get("minLength"));
        assertEquals(64, schema.get("maxLength"));
        assertEquals("^[A-Za-z0-9_-]+$", schema.get("pattern"));
        Map<?, ?> target = JsonPath.read(response.body(), "$.components.schemas.UploadSession.properties.singleUploadTarget");
        boolean nullable = target.get("type") instanceof List<?> types && types.contains("null") && !target.containsKey("$ref") && !target.containsKey("allOf");
        if (target.get("anyOf") instanceof List<?> alternatives) {
            nullable |= alternatives.stream().anyMatch(value -> "null".equals(((Map<?, ?>) value).get("type")));
        }
        assertTrue(nullable, () -> "OpenAPI 3.1 must allow a null upload target: " + target);
    }

    private MinioClient minio() {
        return MinioClient.builder().endpoint(storage.endpoint()).credentials(storage.accessKey(), storage.secretKey()).region(storage.region()).build();
    }

    private int put(Map<String, Object> target, byte[] data) throws Exception {
        var request = HttpRequest.newBuilder(URI.create((String) target.get("url"))).timeout(Duration.ofSeconds(10));
        Map<?, ?> headers = (Map<?, ?>) target.get("headers");
        request.header("Content-Type", (String) headers.get("Content-Type"));
        if (headers.containsKey("x-amz-checksum-sha256")) request.header("x-amz-checksum-sha256", (String) headers.get("x-amz-checksum-sha256"));
        return client.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Map<String, Object> snapshot(String id) { return jdbc.queryForMap("SELECT * FROM media_upload_session WHERE id=?", id); }
    private LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "query-upload-test").accessToken(); }
    private UserAccount fixture() {
        UserAccount user = LoginTestSupport.account();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail("get-upload-it-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        userIds.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }
    private HttpResponse<String> create(String token, String key) throws Exception {
        return create(token, key, Map.of("scene", "CONTENT_IMAGE", "fileName", "image.png", "contentType", "image/png", "sizeBytes", 68));
    }
    private HttpResponse<String> create(String token, String key, Map<String, Object> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/media/uploads"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String id, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/media/uploads/" + id)).timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
