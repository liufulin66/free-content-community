package com.freecontent.community.content.review.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.content.review.mapper.AdminReviewMediaPreviewMapper;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.media.storage.MediaReadSigner;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class AdminReviewMediaPreviewFlowIT {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AxoAAAAASUVORK5CYII=");
    private static final String PREFIX = "test:admin-review-media:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StorageProperties storage;
    @MockitoSpyBean private AdminReviewMediaPreviewMapper references;
    @MockitoSpyBean private MediaObjectReader objects;
    @MockitoSpyBean private MediaReadSigner signer;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> actors = new ArrayList<>(), objectKeys = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.admin-review-query", "content.media-preview", "content.admin-review-media-preview"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        registry.add("app.content.admin-review-media-preview.user-per-minute-limit", () -> 3);
    }
    @AfterEach void cleanup() throws Exception {
        try (var minio = minio()) { for (String key : objectKeys) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build()); }
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='content_review_media_preview_audit'", Integer.class) == 1)
            for (String actor : actors) jdbc.update("DELETE FROM content_review_media_preview_audit WHERE actor_id=? OR content_id IN (SELECT id FROM community_content WHERE author_id=?)", actor, actor);
        for (String actor : actors) ContentTestSupport.clearReviews(jdbc, actor);
        for (String actor : actors) {
            jdbc.update("UPDATE community_content SET working_revision_id=NULL,published_revision_id=NULL WHERE author_id=?", actor);
            jdbc.update("DELETE a FROM content_revision_asset a JOIN content_revision r ON r.id=a.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", actor);
            jdbc.update("DELETE r FROM content_revision r JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", actor);
            jdbc.update("DELETE FROM community_content WHERE author_id=?", actor);
        }
        for (String actor : actors) { jdbc.update("DELETE FROM media_asset WHERE owner_id=?", actor); jdbc.update("DELETE FROM app_user_role WHERE user_id=?", actor); users.deleteById(actor); }
    }

    @Test void reviewersCanObtainAReadUrlForTheFrozenRevisionsReadyImage() throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author);
        ready(author, seed.asset(), PNG, "image/png");
        var response = access(seed.review(), seed.asset(), token(actor));
        assertEquals(200, response.statusCode());
        Map<String,Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("assetId","url","expiresAt","contentType"), data.keySet());
        assertEquals(seed.asset(), data.get("assetId")); assertEquals("image/png", data.get("contentType"));
        String url = (String) data.get("url"); assertTrue(url.contains("X-Amz-Expires=300"));
        Instant expires = Instant.parse((String) data.get("expiresAt")); assertTrue(expires.isAfter(Instant.now()) && expires.isBefore(Instant.now().plusSeconds(301)));
        var bytes = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, bytes.statusCode()); assertArrayEquals(PNG, bytes.body());
        assertEquals("image/png", bytes.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(bytes.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var audit = jdbc.queryForMap("SELECT * FROM content_review_media_preview_audit WHERE actor_id=?", actor.getId());
        assertEquals(seed.review(), audit.get("review_id")); assertEquals(seed.revision(), audit.get("revision_id"));
        assertEquals(seed.content(), audit.get("content_id")); assertEquals(seed.asset(), audit.get("asset_id"));
        assertEquals("ADMIN_REVIEW_MEDIA_PREVIEW", audit.get("action"));
        assertEquals(JsonPath.read(response.body(), "$.requestId"), audit.get("request_id"));
        assertEquals(expires, ((LocalDateTime) audit.get("expires_at")).toInstant(ZoneOffset.UTC));
        assertFalse(audit.values().stream().anyMatch(value -> value instanceof String text && (text.contains("X-Amz-") || text.startsWith("private/"))));
        var unsigned = client.send(HttpRequest.newBuilder(URI.create(url.substring(0, url.indexOf('?')))).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertTrue(unsigned.statusCode() == 403 || unsigned.statusCode() == 404);
        var put = client.send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1})).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(403, put.statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING_UPLOAD", "PROCESSING", "FAILED"})
    void unreadyReferencedAssetsReturn409WithoutSigningOrAuditing(String state) throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER"));
        jdbc.update("UPDATE media_asset SET state=? WHERE id=?", state, seed.asset());
        assertProblem(access(seed.review(), seed.asset(), token(actor)), 409, "MEDIA_NOT_READY");
        assertEquals(0, auditCount(actor)); verifyNoInteractions(objects, signer);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_REVIEW", "UNREFERENCED", "OTHER_OWNER", "WRONG_SCENE", "VIDEO_IN_ARTICLE"})
    void referenceOwnershipAndUsageAreRequiredBeforeRevealingReadiness(String problem) throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author);
        String review = seed.review(), asset = seed.asset();
        switch (problem) {
            case "MISSING_REVIEW" -> review = id();
            case "UNREFERENCED" -> asset = asset(author, "CONTENT_IMAGE", "PROCESSING");
            case "OTHER_OWNER" -> jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?", actor.getId(), asset);
            case "WRONG_SCENE" -> jdbc.update("UPDATE media_asset SET scene='AVATAR' WHERE id=?", asset);
            default -> { jdbc.update("UPDATE media_asset SET scene='CONTENT_VIDEO' WHERE id=?", asset); jdbc.update("UPDATE content_revision_asset SET usage_type='VIDEO' WHERE revision_id=?", seed.revision()); }
        }
        assertProblem(access(review, asset, token(actor)), 404, "RESOURCE_NOT_FOUND");
        verifyNoInteractions(objects, signer); assertEquals(0, auditCount(actor));
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "APPROVED", "REJECTED", "CANCELLED", "INVALIDATED"})
    void terminalReviewsAndDeletedContentsCanStillPreviewTheirOwnFrozenMedia(String state) throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author); ready(author, seed.asset(), PNG, "image/png");
        if (!state.equals("PENDING")) {
            jdbc.update("UPDATE community_content SET active_review_id=NULL,working_revision_id=NULL,lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?", seed.content());
            jdbc.update("UPDATE content_review SET state=? WHERE id=?", state, seed.review());
            jdbc.update("UPDATE content_revision SET status=? WHERE id=?", state.equals("INVALIDATED") ? "CANCELLED" : state, seed.revision());
        }
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        assertEquals(200, access(seed.review(), seed.asset(), token(actor)).statusCode()); assertEquals(1, auditCount(actor));
    }

    @Test void currentDraftMediaCannotReplaceTheFrozenReviewReference() throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author); ready(author, seed.asset(), PNG, "image/png");
        String next = id(), nextAsset = asset(author, "CONTENT_IMAGE", "PROCESSING"); ready(author, nextAsset, PNG, "image/png");
        jdbc.update("UPDATE content_review SET state='REJECTED' WHERE id=?", seed.review());
        jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?", seed.revision());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','新稿','正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", next, seed.content());
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=NULL WHERE id=?", next, seed.content());
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", next, nextAsset);
        String token = token(actor);
        assertProblem(access(seed.review(), nextAsset, token), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, access(seed.review(), seed.asset(), token).statusCode()); assertEquals(1, auditCount(actor));
    }

    @ParameterizedTest @ValueSource(strings = {"MODERATOR", "ADMIN"})
    void writeBlockedReviewersCanReadOwnMediaWithoutTheUserRole(String role) throws Exception {
        var actor = fixture(role); var seed = review(actor); ready(actor, seed.asset(), PNG, "image/png");
        jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='USER'", actor.getId());
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", actor.getId());
        assertEquals(200, access(seed.review(), seed.asset(), token(actor)).statusCode());
    }

    @Test void normalAuthorsAndMissingIdentitiesCannotObtainBackendPreviewUrls() throws Exception {
        var author = fixture("USER"); var seed = review(author);
        assertProblem(access(seed.review(), seed.asset(), token(author)), 403, "PERMISSION_DENIED");
        assertProblem(access(seed.review(), seed.asset(), null), 401, "AUTHENTICATION_REQUIRED");
        verifyNoInteractions(references, objects, signer);
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "NO_REVIEW_ROLE", "DISABLED"})
    void revokedEligibilityDuringObjectVerificationPreventsTheFinalGrant(String change) throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author); ready(author, seed.asset(), PNG, "image/png");
        doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); call.callRealMethod(); changeAccount(actor, change); return null; }).when(objects).verify(any());
        assertProblem(access(seed.review(), seed.asset(), token(actor)), change.equals("AUTH_VERSION") ? 401 : 403,
                switch (change) { case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED"; case "DISABLED" -> "ACCOUNT_RESTRICTED"; default -> "PERMISSION_DENIED"; });
        verifyNoInteractions(signer); assertEquals(0, auditCount(actor));
    }

    @ParameterizedTest @ValueSource(strings = {"REMOVE_REFERENCE", "OWNER", "FAILED", "KEY", "ETAG", "SIZE", "CONTENT_TYPE", "STORAGE", "REVIEW_REVISION"})
    void targetOrObjectBindingChangesDuringHeadAreNeverSignedFromStaleProof(String change) throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); var other = fixture("USER"); var seed = review(author); ready(author, seed.asset(), PNG, "image/png");
        doAnswer(call -> {
            call.callRealMethod();
            switch (change) {
                case "REMOVE_REFERENCE" -> jdbc.update("DELETE FROM content_revision_asset WHERE revision_id=?", seed.revision());
                case "OWNER" -> jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?", other.getId(), seed.asset());
                case "FAILED" -> jdbc.update("UPDATE media_asset SET state='FAILED' WHERE id=?", seed.asset());
                case "KEY" -> jdbc.update("UPDATE media_asset SET ready_object_key=? WHERE id=?", "private/" + author.getId() + "/" + seed.asset() + "/ready/" + id(), seed.asset());
                case "ETAG" -> jdbc.update("UPDATE media_asset SET ready_object_etag='changed' WHERE id=?", seed.asset());
                case "SIZE" -> jdbc.update("UPDATE media_asset SET actual_size_bytes=actual_size_bytes+1 WHERE id=?", seed.asset());
                case "CONTENT_TYPE" -> jdbc.update("UPDATE media_asset SET actual_content_type='image/jpeg' WHERE id=?", seed.asset());
                case "STORAGE" -> jdbc.update("UPDATE media_asset SET ready_storage_id=? WHERE id=?", "f".repeat(64), seed.asset());
                default -> {
                    String next = id(); jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?", seed.revision());
                    jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'IN_REVIEW','其他稿','正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", next, seed.content());
                    jdbc.update("UPDATE content_review SET revision_id=? WHERE id=?", next, seed.review());
                    jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", next, seed.content());
                    jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", next, seed.asset());
                }
            }
            return null;
        }).when(objects).verify(any());
        int status = change.equals("REMOVE_REFERENCE") || change.equals("OWNER") ? 404 : change.equals("FAILED") ? 409 : 503;
        assertProblem(access(seed.review(), seed.asset(), token(actor)), status, status == 404 ? "RESOURCE_NOT_FOUND" : status == 409 ? "MEDIA_NOT_READY" : "DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(signer); assertEquals(0, auditCount(actor));
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "ETAG", "SIZE", "NO_BINDING", "NO_METADATA", "SOURCE_KEY", "STORAGE"})
    void readyRecordsRequireAnExistingMatchingIndependentResultObject(String damage) throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author); String key = ready(author, seed.asset(), PNG, "image/png");
        switch (damage) {
            case "MISSING" -> { try (var minio = minio()) { minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build()); } }
            case "ETAG" -> jdbc.update("UPDATE media_asset SET ready_object_etag='wrong' WHERE id=?", seed.asset());
            case "SIZE" -> jdbc.update("UPDATE media_asset SET actual_size_bytes=999 WHERE id=?", seed.asset());
            case "NO_BINDING" -> jdbc.update("UPDATE media_asset SET ready_object_key=NULL,ready_object_etag=NULL,ready_storage_id=NULL WHERE id=?", seed.asset());
            case "NO_METADATA" -> jdbc.update("UPDATE media_asset SET actual_content_type=NULL WHERE id=?", seed.asset());
            case "SOURCE_KEY" -> jdbc.update("UPDATE media_asset SET ready_object_key=storage_key WHERE id=?", seed.asset());
            default -> jdbc.update("UPDATE media_asset SET ready_storage_id=? WHERE id=?", "f".repeat(64), seed.asset());
        }
        assertProblem(access(seed.review(), seed.asset(), token(actor)), 503, "DEPENDENCY_UNAVAILABLE"); verifyNoInteractions(signer); assertEquals(0, auditCount(actor));
    }

    @Test void auditFailuresNeverReturnAnUnauditedSignature() throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author); ready(author, seed.asset(), PNG, "image/png");
        doThrow(new TransientDataAccessResourceException("sensitive audit detail")).when(references).insertAudit(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        var response = access(seed.review(), seed.asset(), token(actor)); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("X-Amz-")); assertFalse(response.body().contains("sensitive")); assertEquals(0, auditCount(actor));
    }

    @Test void headRunsOutsideTransactionsAndLocalSigningRunsInsideTheFinalTransaction() throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author); ready(author, seed.asset(), PNG, "image/png");
        doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return call.callRealMethod(); }).when(objects).verify(any());
        doAnswer(call -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return call.callRealMethod(); }).when(signer).sign(any(), any(), anyInt());
        assertEquals(200, access(seed.review(), seed.asset(), token(actor)).statusCode());
    }

    @Test void successfulRepeatedGrantsEachCountAndProduceTheirOwnAudit() throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author); ready(author, seed.asset(), PNG, "image/png"); String token = token(actor);
        for (int i = 0; i < 3; i++) assertEquals(200, access(seed.review(), seed.asset(), token).statusCode());
        assertProblem(access(seed.review(), seed.asset(), token), 429, "RATE_LIMITED"); assertEquals(3, auditCount(actor));
        assertTrue(redis.keys(PREFIX + "{content-media-preview}:*").isEmpty()); assertTrue(redis.keys(PREFIX + "{content-admin-review-query}:*").isEmpty());
    }

    @Test void unreadyAndNotFoundRequestsAlsoConsumeTheIndependentQuota() throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); String token = token(actor);
        assertProblem(access(seed.review(), seed.asset(), token), 409, "MEDIA_NOT_READY");
        assertProblem(access(seed.review(), id(), token), 404, "RESOURCE_NOT_FOUND");
        assertProblem(access(id(), seed.asset(), token), 404, "RESOURCE_NOT_FOUND");
        var limited = access(seed.review(), seed.asset(), token); assertProblem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    @Test void redisFailuresPreventAllDatabaseProofAndSigningWork() throws Exception {
        var actor = fixture("ADMIN"); var seed = review(fixture("USER"));
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-admin-review-media-preview}:")), any(), any());
        assertProblem(access(seed.review(), seed.asset(), token(actor)), 503, "DEPENDENCY_UNAVAILABLE"); verifyNoInteractions(references, objects, signer);
    }

    @Test void referencedReadyVideoSupportsActualRangeReads() throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author);
        jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?", seed.content()); jdbc.update("UPDATE media_asset SET scene='CONTENT_VIDEO' WHERE id=?", seed.asset());
        jdbc.update("UPDATE content_revision_asset SET usage_type='VIDEO' WHERE revision_id=?", seed.revision());
        byte[] video = HexFormat.of().parseHex("00000018667479706d703432000000006d70343269736f6d000000086d646174");
        ready(author, seed.asset(), video, "video/mp4"); var response = access(seed.review(), seed.asset(), token(actor)); assertEquals(200, response.statusCode());
        String url = JsonPath.read(response.body(), "$.data.url");
        var range = client.send(HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=0-3").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, range.statusCode()); assertArrayEquals(Arrays.copyOfRange(video, 0, 4), range.body());
    }

    @Test void swaggerDefinesOnlyTwoPathIdsAndTheMediaAccessResponse() throws Exception {
        var response = get("/v3/api-docs", null); assertEquals(200, response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/admin/reviews/{reviewId}/media/{assetId}/access'].get");
        assertEquals("getReviewMediaAccess", operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        List<Map<String,Object>> parameters = (List<Map<String,Object>>) operation.get("parameters");
        assertEquals(Set.of("reviewId", "assetId"), parameters.stream().map(p -> (String) p.get("name")).collect(java.util.stream.Collectors.toSet()));
        for (var parameter : parameters) { assertEquals("path", parameter.get("in")); assertEquals(true, parameter.get("required")); }
        Map<String,Object> media = JsonPath.read(response.body(), "$.components.schemas.MediaAccess");
        assertEquals(Set.of("assetId", "url", "expiresAt", "contentType"), new HashSet<>((List<?>) media.get("required")));
    }

    private int auditCount(UserAccount actor) { return jdbc.queryForObject("SELECT COUNT(*) FROM content_review_media_preview_audit WHERE actor_id=?", Integer.class, actor.getId()); }
    private void changeAccount(UserAccount actor, String change) {
        switch (change) {
            case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", actor.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", actor.getId());
            case "NO_REVIEW_ROLE" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code IN ('MODERATOR','ADMIN')", actor.getId());
            default -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", actor.getId());
        }
    }
    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private record Seed(String content, String revision, String review, String asset) { }
    private Seed review(UserAccount author) {
        String content = id(), revision = id(), asset = asset(author, "CONTENT_IMAGE", "PROCESSING");
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'IN_REVIEW','冻结标题','正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content);
        String review = ContentTestSupport.pending(jdbc, content, revision);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", revision, review, content);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", revision, asset);
        return new Seed(content, revision, review, asset);
    }
    private String asset(UserAccount author, String scene, String state) {
        String asset = id(), type = scene.equals("CONTENT_VIDEO") ? "video/mp4" : "image/png";
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,?,'fixture',?,68,?,?,UTC_TIMESTAMP(6))",
                asset, author.getId(), scene, type, "private/" + author.getId() + "/" + asset + "/source", state);
        return asset;
    }
    private String ready(UserAccount author, String asset, byte[] bytes, String type) throws Exception {
        String key = "private/" + author.getId() + "/" + asset + "/ready/" + id(); objectKeys.add(key); String etag;
        try (var minio = minio()) {
            etag = minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(key).contentType(type)
                    .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L).build()).etag();
        }
        jdbc.update("UPDATE media_asset SET state='READY',actual_content_type=?,actual_size_bytes=?,ready_object_key=?,ready_object_etag=?,ready_storage_id=? WHERE id=?",
                type, bytes.length, key, etag, storage.identity(), asset);
        return key;
    }
    private MinioClient minio() { return MinioClient.builder().endpoint(storage.endpoint()).region(storage.region()).credentials(storage.accessKey(), storage.secretKey()).build(); }
    private UserAccount fixture(String role) {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("admin-review-media-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        actors.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", user.getId(), role);
        return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "admin-review-media-test").accessToken(); }
    private HttpResponse<String> access(String review, String asset, String token) throws Exception {
        return get("/api/v1/admin/reviews/" + review + "/media/" + asset + "/access", token);
    }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
