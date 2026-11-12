package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.browse.mapper.PublishedMediaAccessMapper;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.media.storage.MediaReadSigner;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class PublishedMediaAccessFlowIT {
    private static final String PREFIX = "test:published-media:" + UUID.randomUUID() + ":";
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AxoAAAAASUVORK5CYII=");
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private PublishedMediaAccessMapper references;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StorageProperties storage;
    @Autowired private SqlSession sqlSession;
    @MockitoSpyBean private MediaObjectReader objects;
    @MockitoSpyBean private MediaReadSigner signer;
    private final List<String> owners = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.public-query", "content.public-media-access")) {
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        reset(objects, signer, redis, references);
        if (!objectKeys.isEmpty()) try (var minio = minio()) {
            for (String key : objectKeys) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
        }
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            ContentTestSupport.clearReviews(jdbc, owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test
    void aReferencedButUnreadyTargetIs409ForAnonymousVisitors() throws Exception {
        var author = author();
        String asset = asset(author);
        var published = published(author, asset);
        problem(access(published.content(), asset, null), 409, "MEDIA_NOT_READY");
        verifyNoInteractions(objects, signer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNREFERENCED", "OTHER_OWNER", "WRONG_SCENE", "DRAFT_ONLY", "HISTORICAL_ONLY", "MISSING_CONTENT",
            "UNPUBLISHED", "WITHDRAWN", "TAKEN_DOWN", "DELETED", "DRAFT", "REJECTED"})
    void onlyCorrectlyOwnedReferencesInTheCurrentPublicRevisionCanBeRequested(String change) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        String content = seed.content();
        switch (change) {
            case "UNREFERENCED" -> jdbc.update("DELETE FROM content_revision_asset WHERE revision_id=?", seed.revision());
            case "OTHER_OWNER" -> jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?", author().getId(), asset);
            case "WRONG_SCENE" -> jdbc.update("UPDATE media_asset SET scene='AVATAR' WHERE id=?", asset);
            case "DRAFT_ONLY" -> {
                jdbc.update("DELETE FROM content_revision_asset WHERE revision_id=?", seed.revision());
                String draft = revision(seed.content(), 2, "DRAFT");
                jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", draft, asset);
                jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", draft, content);
            }
            case "HISTORICAL_ONLY" -> jdbc.update("UPDATE community_content SET published_revision_id=? WHERE id=?",
                    revision(content, 2, "APPROVED"), content);
            case "MISSING_CONTENT" -> content = id();
            case "UNPUBLISHED", "WITHDRAWN", "TAKEN_DOWN" -> jdbc.update("UPDATE community_content SET publication_status=? WHERE id=?", change, content);
            case "DELETED" -> jdbc.update("UPDATE community_content SET lifecycle='DELETED' WHERE id=?", content);
            default -> jdbc.update("UPDATE content_revision SET status=? WHERE id=?", change, seed.revision());
        }
        problem(access(content, asset, null), 404, "RESOURCE_NOT_FOUND");
        verifyNoInteractions(objects, signer);
    }

    @Test
    void aReadyPublishedReferenceReturnsAShortLivedPrivateGetUrlThatLoadsTheFile() throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        var response = access(seed.content(), asset, null);
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("assetId", "url", "expiresAt", "contentType"), data.keySet());
        assertEquals(asset, data.get("assetId"));
        assertEquals("image/png", data.get("contentType"));
        String url = (String) data.get("url");
        assertTrue(url.contains("/ready/"));
        assertFalse(url.contains("/source"));
        assertTrue(url.contains("X-Amz-Expires=300"));
        assertTrue(Instant.parse((String) data.get("expiresAt")).isAfter(Instant.now()));
        assertTrue(Instant.parse((String) data.get("expiresAt")).isBefore(Instant.now().plusSeconds(301)));
        var download = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertArrayEquals(PNG, download.body());
        assertEquals("image/png", download.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(download.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void aMissingReadyObjectReturns503BeforeAnyUrlIsIssued() throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        String key = ready(author, asset);
        try (var minio = minio()) {
            minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
        }
        problem(access(seed.content(), asset, null), 503, "DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(signer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "REFERENCE_REMOVED", "TARGET_FAILED", "OTHER_FAILED", "REVISION_CHANGED", "BINDING_CHANGED"})
    void changesDuringHeadAreRejectedBeforeTheFinalSignature(String change) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        String other = asset(author);
        jdbc.update("UPDATE media_asset SET state='READY' WHERE id=?", other);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)", seed.revision(), other);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            call.callRealMethod();
            switch (change) {
                case "WITHDRAWN" -> jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", seed.content());
                case "REFERENCE_REMOVED" -> jdbc.update("DELETE FROM content_revision_asset WHERE revision_id=? AND asset_id=?", seed.revision(), asset);
                case "TARGET_FAILED" -> jdbc.update("UPDATE media_asset SET state='FAILED' WHERE id=?", asset);
                case "OTHER_FAILED" -> jdbc.update("UPDATE media_asset SET state='FAILED' WHERE id=?", other);
                case "REVISION_CHANGED" -> {
                    String next = revision(seed.content(), 2, "APPROVED");
                    jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", next, asset);
                    jdbc.update("UPDATE community_content SET published_revision_id=? WHERE id=?", next, seed.content());
                }
                default -> jdbc.update("UPDATE media_asset SET ready_object_key=? WHERE id=?",
                        "private/" + author.getId() + "/" + asset + "/ready/" + id(), asset);
            }
            return null;
        }).when(objects).verify(any());
        var response = access(seed.content(), asset, null);
        switch (change) {
            case "TARGET_FAILED" -> problem(response, 409, "MEDIA_NOT_READY");
            case "REVISION_CHANGED", "BINDING_CHANGED" -> problem(response, 503, "DEPENDENCY_UNAVAILABLE");
            default -> problem(response, 404, "RESOURCE_NOT_FOUND");
        }
        verifyNoInteractions(signer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTENT", "REVISION", "REFERENCE", "TARGET", "OTHER"})
    void theFinalSignatureHoldsTheRowsThatDetermineItsAuthorization(String row) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        String other = asset(author);
        jdbc.update("UPDATE media_asset SET state='READY' WHERE id=?", other);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)", seed.revision(), other);
        var pool = Executors.newSingleThreadExecutor();
        var blocked = new AtomicBoolean();
        var writer = new AtomicReference<Future<?>>();
        try {
            doAnswer(call -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                var started = new CountDownLatch(1);
                writer.set(pool.submit(() -> {
                    try (var connection = jdbc.getDataSource().getConnection()) {
                        String sql = switch (row) {
                            case "CONTENT" -> "UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?";
                            case "REVISION" -> "UPDATE content_revision SET status='REJECTED' WHERE id=?";
                            case "REFERENCE" -> "DELETE FROM content_revision_asset WHERE revision_id=? AND usage_type='COVER'";
                            default -> "UPDATE media_asset SET state='FAILED' WHERE id=?";
                        };
                        String target = switch (row) {
                            case "CONTENT" -> seed.content();
                            case "REVISION", "REFERENCE" -> seed.revision();
                            case "TARGET" -> asset;
                            default -> other;
                        };
                        try (var statement = connection.prepareStatement(sql)) {
                            statement.setString(1, target);
                            started.countDown();
                            statement.executeUpdate();
                        }
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                }));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                try { writer.get().get(300, TimeUnit.MILLISECONDS); }
                catch (TimeoutException expected) { blocked.set(true); }
                return call.callRealMethod();
            }).when(signer).sign(any(), any(), anyInt());
            var response = access(seed.content(), asset, null);
            assertEquals(200, response.statusCode(), response.body());
            writer.get().get(5, TimeUnit.SECONDS);
            assertTrue(blocked.get(), "授权依据在签发完成前不得被并发改写：" + row);
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "VIEWER"})
    void eitherExhaustedSigningBudgetStopsDatabaseAndStorageWork(String scope) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        String token = null;
        String key = sourceKey(), quota = "120";
        if (scope.equals("VIEWER")) {
            var viewer = author();
            token = sessions.create(viewer.getId(), 1, "published-media-test").accessToken();
            key = PREFIX + "{content-public-media-access}:user:" + TokenDigest.sha256(viewer.getId());
            quota = "60";
        }
        redis.opsForValue().set(key, quota, Duration.ofMinutes(1));
        var response = access(seed.content(), asset, token);
        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        verifyNoInteractions(references, objects, signer);
    }

    @Test
    void anonymousAndAuthenticatedSigningShareOnlyTheirIndependentSourceBudget() throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        var viewer = author();
        String token = sessions.create(viewer.getId(), 1, "published-media-test").accessToken();
        assertEquals(200, access(seed.content(), asset, null).statusCode());
        assertEquals(200, access(seed.content(), asset, token).statusCode());
        assertEquals("2", redis.opsForValue().get(sourceKey()));
        assertEquals("1", redis.opsForValue().get(PREFIX + "{content-public-media-access}:user:" + TokenDigest.sha256(viewer.getId())));
        assertNull(redis.opsForValue().get(PREFIX + "{content-public-media-access}:user:" + TokenDigest.sha256(author.getId())));
        assertEquals(200, request("/api/v1/contents/" + seed.content(), null).statusCode());
        assertEquals("1", redis.opsForValue().get(PREFIX + "{content-public-query}:source:" + TokenDigest.sha256("127.0.0.1")));
        assertEquals("2", redis.opsForValue().get(sourceKey()));
    }

    private String sourceKey() {
        return PREFIX + "{content-public-media-access}:source:" + TokenDigest.sha256("127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_UPLOAD", "PROCESSING", "FAILED"})
    void allUnreadyTargetStatesReturn409BeforeStorageAccess(String state) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        jdbc.update("UPDATE media_asset SET state=? WHERE id=?", state, asset);
        problem(access(seed.content(), asset, null), 409, "MEDIA_NOT_READY");
        verifyNoInteractions(objects, signer);
    }

    @Test
    void anotherUnreadyReferenceMakesTheContent404EvenWhenTheRequestedTargetIsReady() throws Exception {
        var author = author();
        String asset = asset(author), other = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)", seed.revision(), other);
        problem(access(seed.content(), asset, null), 404, "RESOURCE_NOT_FOUND");
        verifyNoInteractions(objects, signer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BINDING", "SOURCE_KEY", "STORAGE", "CONTENT_TYPE", "SCENE_TYPE", "ETAG", "SIZE"})
    void incompleteOrMismatchedReadyBindingsReturn503WithoutASourceFallback(String change) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        switch (change) {
            case "BINDING" -> jdbc.update("UPDATE media_asset SET ready_object_key=NULL,ready_object_etag=NULL,ready_storage_id=NULL WHERE id=?", asset);
            case "SOURCE_KEY" -> jdbc.update("UPDATE media_asset SET ready_object_key=storage_key WHERE id=?", asset);
            case "STORAGE" -> jdbc.update("UPDATE media_asset SET ready_storage_id=? WHERE id=?", "0".repeat(64), asset);
            case "CONTENT_TYPE" -> jdbc.update("UPDATE media_asset SET actual_content_type='text/html' WHERE id=?", asset);
            case "SCENE_TYPE" -> jdbc.update("UPDATE media_asset SET actual_content_type='video/mp4' WHERE id=?", asset);
            case "ETAG" -> jdbc.update("UPDATE media_asset SET ready_object_etag='changed' WHERE id=?", asset);
            default -> jdbc.update("UPDATE media_asset SET actual_size_bytes=actual_size_bytes+1 WHERE id=?", asset);
        }
        problem(access(seed.content(), asset, null), 503, "DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(signer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POINTER", "TIME"})
    void inconsistentPublicationMetadataIs503(String change) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        if (change.equals("POINTER")) jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?", seed.content());
        else jdbc.update("UPDATE community_content SET first_published_at=NULL,published_at=NULL WHERE id=?", seed.content());
        problem(access(seed.content(), asset, null), 503, "DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(objects, signer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOT", "FINAL_ROOT", "ASSETS", "TAGS", "REDIS", "HEAD", "SIGNER"})
    void dependencyFailuresAre503WithoutInternalDetails(String dependency) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        var failure = new TransientDataAccessResourceException("sensitive SQL detail");
        switch (dependency) {
            case "ROOT" -> doThrow(failure).when(references).lockPublication(anyString());
            case "FINAL_ROOT" -> {
                var calls = new AtomicInteger();
                doAnswer(call -> {
                    if (calls.incrementAndGet() == 2) throw failure;
                    return sqlSession.selectOne(PublishedMediaAccessMapper.class.getName() + ".lockPublication", Map.of("content", seed.content()));
                }).when(references).lockPublication(seed.content());
            }
            case "ASSETS" -> doThrow(failure).when(references).lockAssets(anyString(), anyList());
            case "TAGS" -> doThrow(failure).when(references).lockTags(anyString());
            case "REDIS" -> doThrow(new RedisConnectionFailureException("sensitive Redis detail"))
                    .when(redis).execute(any(RedisScript.class), anyList(), any());
            case "HEAD" -> doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(objects).verify(any());
            default -> doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(signer).sign(any(), any(), anyInt());
        }
        var result = access(seed.content(), asset, null);
        problem(result, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(result.body().contains("sensitive"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "WRITE_BLOCKED", "TARGET_DISABLED", "DISABLED", "UNVERIFIED", "STALE", "REVOKED"})
    void realSessionsAreValidatedWhilePublicAuthorRestrictionsRemainSeparate(String state) throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        var viewer = author();
        var issued = sessions.create(viewer.getId(), 1, "published-media-test");
        switch (state) {
            case "WRITE_BLOCKED", "DISABLED" -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", state, viewer.getId());
            case "TARGET_DISABLED" -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?", viewer.getId());
            case "STALE" -> jdbc.update("UPDATE app_user SET auth_version=2 WHERE id=?", viewer.getId());
            case "REVOKED" -> sessions.revoke(viewer.getId(), issued.session().sessionId(), TokenDigest.sha256(issued.accessToken()));
        }
        var result = access(seed.content(), asset, issued.accessToken());
        if (Set.of("ACTIVE", "WRITE_BLOCKED", "TARGET_DISABLED").contains(state)) assertEquals(200, result.statusCode(), result.body());
        else {
            problem(result, 401, "AUTHENTICATION_REQUIRED");
            assertNull(redis.opsForValue().get(sourceKey()));
            verifyNoInteractions(references, objects, signer);
        }
    }

    @Test
    void badParametersSpendNoQuotaAndRepeatedSigningDoesNotWriteBusinessData() throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        ready(author, asset);
        problem(access("bad.id", asset, null), 422, "VALIDATION_ERROR");
        problem(access(seed.content(), "bad.id", null), 422, "VALIDATION_ERROR");
        problem(request("/api/v1/contents/" + seed.content() + "/media/" + asset + "/access?revisionId=other", null), 400, "MALFORMED_REQUEST");
        assertNull(redis.opsForValue().get(sourceKey()));
        var before = businessRows(author, seed, asset);
        assertEquals(200, access(seed.content(), asset, null).statusCode());
        assertEquals(200, access(seed.content(), asset, null).statusCode());
        assertEquals(before, businessRows(author, seed, asset));
        assertEquals("2", redis.opsForValue().get(sourceKey()));
    }

    private List<Object> businessRows(UserAccount author, Seed seed, String asset) {
        return List.of(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", author.getId()),
                jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()),
                jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?", asset),
                jdbc.queryForList("SELECT * FROM content_review_outbox WHERE content_id=?", seed.content()),
                jdbc.queryForList("SELECT * FROM content_media_preview_audit WHERE content_id=?", seed.content()),
                jdbc.queryForList("SELECT * FROM content_review_media_preview_audit WHERE content_id=?", seed.content()));
    }

    @Test
    void openApiMatchesThePublicMediaOperationAndSchema() throws Exception {
        var result = request("/v3/api-docs", null);
        assertEquals(200, result.statusCode());
        Map<String, Object> operation = JsonPath.read(result.body(), "$.paths['/api/v1/contents/{contentId}/media/{assetId}/access'].get");
        assertEquals("getPublishedMediaAccess", operation.get("operationId"));
        assertEquals(List.of(Map.of(), Map.of("bearerAuth", List.of())), operation.get("security"));
        assertEquals(Set.of("200", "400", "401", "404", "409", "422", "429", "500", "503"), ((Map<?, ?>) operation.get("responses")).keySet());
        Map<String, Object> schema = JsonPath.read(result.body(), "$.components.schemas.MediaAccess");
        var fields = Set.of("assetId", "url", "expiresAt", "contentType");
        assertEquals(fields, ((Map<?, ?>) schema.get("properties")).keySet());
        assertEquals(fields, new java.util.HashSet<>((List<?>) schema.get("required")));
    }

    @Test
    void aReadyVideoReferenceSupportsShortLivedRangeGet() throws Exception {
        var author = author();
        String asset = asset(author);
        var seed = published(author, asset);
        jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?", seed.content());
        jdbc.update("UPDATE content_revision_asset SET usage_type='VIDEO' WHERE revision_id=?", seed.revision());
        jdbc.update("UPDATE media_asset SET scene='CONTENT_VIDEO',file_name='test.mp4',declared_content_type='video/mp4' WHERE id=?", asset);
        byte[] bytes = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        ready(author, asset, bytes, "video/mp4");
        var result = access(seed.content(), asset, null);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals("video/mp4", JsonPath.read(result.body(), "$.data.contentType"));
        String url = JsonPath.read(result.body(), "$.data.url");
        var part = client.send(HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=0-3").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, part.statusCode());
        assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 0, 4), part.body());
        assertEquals("video/mp4", part.headers().firstValue("Content-Type").orElseThrow());
    }

    private String ready(UserAccount author, String asset) throws Exception {
        return ready(author, asset, PNG, "image/png");
    }

    private String ready(UserAccount author, String asset, byte[] bytes, String contentType) throws Exception {
        String key = "private/" + author.getId() + "/" + asset + "/ready/" + id();
        objectKeys.add(key);
        String etag;
        try (var minio = minio()) {
            etag = minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(key).contentType(contentType)
                    .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L).build()).etag();
        }
        jdbc.update("UPDATE media_asset SET state='READY',actual_content_type=?,actual_size_bytes=?,"
                + "ready_object_key=?,ready_object_etag=?,ready_storage_id=? WHERE id=?", contentType, bytes.length, key, etag, storage.identity(), asset);
        return key;
    }

    private MinioClient minio() {
        return MinioClient.builder().endpoint(storage.endpoint()).region(storage.region()).credentials(storage.accessKey(), storage.secretKey()).build();
    }

    private String revision(String content, int number, String state) {
        String revision = id();
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,?,?,'修订标题','修订正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content, number, state);
        return revision;
    }

    private UserAccount author() {
        var author = LoginTestSupport.account();
        author.setId(id());
        author.setEmail("published-media-" + author.getId() + "@example.test");
        author.setEmailNormalized(author.getEmail());
        users.insert(author);
        owners.add(author.getId());
        roles.insertDefaultRole(author.getId(), author.getCreatedAt());
        return author;
    }

    private String asset(UserAccount author) {
        String asset = id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) "
                + "VALUES(?,?,'CONTENT_IMAGE','test.png','image/png',1,?,'PROCESSING',UTC_TIMESTAMP(6))",
                asset, author.getId(), "private/" + author.getId() + "/" + asset + "/source");
        return asset;
    }

    private Seed published(UserAccount author, String asset) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,1,'APPROVED','公开作品','已发布正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", revision, asset);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',"
                + "first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?", revision, content);
        return new Seed(content, revision);
    }

    private HttpResponse<String> access(String content, String asset, String token) throws Exception {
        return request("/api/v1/contents/" + content + "/media/" + asset + "/access", token);
    }

    private HttpResponse<String> request(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("data"));
        assertFalse(response.body().contains("X-Amz-"));
        assertFalse(response.body().contains("private/"));
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private record Seed(String content, String revision) { }
}
