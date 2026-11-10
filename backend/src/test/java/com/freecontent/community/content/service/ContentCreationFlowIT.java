package com.freecontent.community.content.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.content.dto.CreateContentRequest;
import com.freecontent.community.content.mapper.ContentCreationMapper;
import com.freecontent.community.content.mapper.ContentCreationRequestMapper;
import com.freecontent.community.content.ratelimit.ContentCreationRateLimiter;
import com.freecontent.community.auth.service.SessionAuthenticationService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
class ContentCreationFlowIT {
    private static final String PREFIX = "test:content-create:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private ContentCreationMapper contents;
    @MockitoSpyBean private ContentCreationRequestMapper requests;
    @MockitoSpyBean private ContentCreationRateLimiter limiter;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.creation.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='community_content'", Integer.class) == 1) {
                jdbc.update("DELETE FROM content_creation_request WHERE created_by=?", owner);
                jdbc.update("UPDATE community_content SET working_revision_id=NULL,published_revision_id=NULL WHERE author_id=?", owner);
                jdbc.update("DELETE a FROM content_revision_asset a JOIN content_revision r ON r.id=a.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
                jdbc.update("DELETE t FROM content_revision_tag t JOIN content_revision r ON r.id=t.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
                jdbc.update("DELETE r FROM content_revision r JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
                jdbc.update("DELETE FROM community_content WHERE author_id=?", owner);
            }
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARTICLE", "VIDEO"})
    void createsContentAndItsFirstEmptyDraftAtomically(String kind) throws Exception {
        UserAccount author = fixture();

        var response = create(token(author), id(), "{\"kind\":\"" + kind + "\"}");

        assertEquals(201, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "authorId", "kind", "lifecycle", "publicationStatus", "version", "workingRevision",
                "publishedRevision", "activeReviewId", "createdAt", "updatedAt", "deletedAt"), data.keySet());
        assertEquals(author.getId(), data.get("authorId"));
        assertEquals(kind, data.get("kind"));
        assertEquals("ACTIVE", data.get("lifecycle"));
        assertEquals("UNPUBLISHED", data.get("publicationStatus"));
        assertEquals(1, data.get("version"));
        assertNull(data.get("publishedRevision"));
        assertNull(data.get("activeReviewId"));
        assertNull(data.get("deletedAt"));
        Map<String, Object> draft = (Map<String, Object>) data.get("workingRevision");
        assertEquals(data.get("id"), draft.get("contentId"));
        assertEquals(1, draft.get("revisionNumber"));
        assertEquals("DRAFT", draft.get("status"));
        assertEquals("", draft.get("title"));
        assertEquals("", draft.get("bodyMarkdown"));
        assertEquals(List.of(), draft.get("imageAssetIds"));
        assertEquals(List.of(), draft.get("tagIds"));
        assertNull(draft.get("coverAssetId"));
        assertNull(draft.get("videoAssetId"));
        assertEquals("/api/v1/creator/contents/" + data.get("id"), response.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals(draft.get("id"), jdbc.queryForObject("SELECT working_revision_id FROM community_content WHERE id=?", String.class, data.get("id")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, data.get("id")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=? AND state='SUCCEEDED'", Integer.class, author.getId()));
    }

    private UserAccount fixture() {
        UserAccount user = LoginTestSupport.account();
        user.setId(id());
        user.setAuthVersion(1L);
        user.setEmail("content-create-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        owners.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_UPLOAD", "PROCESSING", "FAILED", "READY"})
    void persistsDraftFieldsAndOrderedReferencesWithoutRequiringPublicationReadiness(String state) throws Exception {
        UserAccount author = fixture();
        String first = asset(author, "CONTENT_IMAGE", state), second = asset(author, "CONTENT_IMAGE", state);
        String tag = tag(author, "DISABLED");
        String markdown = "```md\n![example](asset:missing)\n```\n\n![one](asset:" + first + ")\n\n![two][ref]\n\n[ref]: asset:" + second;
        var input = new LinkedHashMap<String, Object>();
        input.put("kind", "ARTICLE"); input.put("title", "  中文😀草稿  "); input.put("bodyMarkdown", markdown);
        input.put("coverAssetId", first); input.put("imageAssetIds", List.of(second, first));
        input.put("videoAssetId", null); input.put("tagIds", List.of(tag));

        var response = create(token(author), id(), json.writeValueAsString(input));

        assertEquals(201, response.statusCode(), response.body());
        Map<String, Object> draft = JsonPath.read(response.body(), "$.data.workingRevision");
        assertEquals(input.get("title"), draft.get("title"));
        assertEquals(markdown, draft.get("bodyMarkdown"));
        assertEquals(List.of(second, first), draft.get("imageAssetIds"));
        assertEquals(first, draft.get("coverAssetId"));
        assertEquals(List.of(tag), draft.get("tagIds"));
        assertEquals(markdown, jdbc.queryForObject("SELECT body_markdown FROM content_revision WHERE id=?", String.class, draft.get("id")));
        assertEquals(List.of(second, first), jdbc.queryForList("SELECT asset_id FROM content_revision_asset WHERE revision_id=? AND usage_type='IMAGE' ORDER BY position", String.class, draft.get("id")));
        assertEquals(List.of(tag), jdbc.queryForList("SELECT tag_id FROM content_revision_tag WHERE revision_id=? ORDER BY position", String.class, draft.get("id")));
        assertEquals(state, jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, first));
    }

    private String asset(UserAccount owner, String scene, String state) {
        String id = id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,?,?,?,1,?,?,UTC_TIMESTAMP(6))",
                id, owner.getId(), scene, "fixture", scene.equals("CONTENT_VIDEO") ? "video/mp4" : "image/png", "private/" + owner.getId() + "/" + id + "/source", state);
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "WRITE_BLOCKED", "NO_USER"})
    void rechecksCurrentCreationEligibilityInsideTheWriteTransaction(String change) throws Exception {
        UserAccount author = fixture(); String token = token(author);
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            switch (change) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", author.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", author.getId());
                case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", author.getId());
                default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, author.getId());
            }
            return principal;
        }).when(authentication).authenticate(token);

        var response = create(token, id(), "{\"kind\":\"ARTICLE\"}");

        int status = change.equals("AUTH_VERSION") ? 401 : 403;
        String code = switch (change) {
            case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED";
            case "UNVERIFIED" -> "EMAIL_VERIFICATION_REQUIRED";
            case "NO_USER" -> "PERMISSION_DENIED";
            default -> "ACCOUNT_RESTRICTED";
        };
        assertProblem(response, status, code);
        assertEquals(0, contentCount(author));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    private long contentCount(UserAccount author) { return jdbc.queryForObject("SELECT COUNT(*) FROM community_content WHERE author_id=?", Long.class, author.getId()); }

    @Test void aVideoDraftCanReferenceItsOwnersPendingVideoWithoutPublishingIt() throws Exception {
        UserAccount author = fixture(); String video = asset(author, "CONTENT_VIDEO", "PROCESSING");
        var response = create(token(author), id(), json.writeValueAsString(Map.of("kind", "VIDEO", "videoAssetId", video)));
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(video, JsonPath.read(response.body(), "$.data.workingRevision.videoAssetId"));
        assertEquals("UNPUBLISHED", JsonPath.read(response.body(), "$.data.publicationStatus"));
        String revision = JsonPath.read(response.body(), "$.data.workingRevision.id");
        assertEquals(video, jdbc.queryForObject("SELECT asset_id FROM content_revision_asset WHERE revision_id=? AND usage_type='VIDEO'", String.class,
                revision));
    }

    @Test void swaggerMatchesCreationDefaultsConstraintsAndNullableWorkspaceFields() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents'].post");
        assertEquals("createContent", operation.get("operationId"));
        Map<String,Object> schema = JsonPath.read(response.body(), "$.components.schemas.CreateContentRequest");
        assertEquals(Set.of("kind"), new HashSet<>((List<String>) schema.get("required")));
        assertEquals(false, schema.get("additionalProperties"));
        Map<String,Map<String,Object>> fields = (Map<String,Map<String,Object>>) schema.get("properties");
        assertEquals(Set.of("kind","title","bodyMarkdown","coverAssetId","imageAssetIds","videoAssetId","tagIds"), fields.keySet());
        Map<String,Map<String,Object>> headers = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents'].post.responses['201'].headers");
        List<Map<String,Object>> parameters = (List<Map<String,Object>>) operation.get("parameters");
        Map<String,Object> key = (Map<String,Object>) parameters.get(0).get("schema");
        Map<String,Object> published = JsonPath.read(response.body(), "$.components.schemas.ContentWorkspace.properties.publishedRevision");
        assertAll(
                () -> assertTrue(headers.containsKey("Location")),
                () -> assertEquals(16, key.get("minLength")), () -> assertEquals(128, key.get("maxLength")),
                () -> assertEquals("", fields.get("title").get("default")), () -> assertEquals("", fields.get("bodyMarkdown").get("default")),
                () -> assertEquals(List.of(), fields.get("imageAssetIds").get("default")), () -> assertEquals(List.of(), fields.get("tagIds").get("default")),
                () -> assertEquals(true, fields.get("imageAssetIds").get("uniqueItems")),
                () -> assertNull(published.get("$ref")), () -> assertTrue(published.get("type") instanceof List<?> types && types.contains("null")));
    }

    @Test
    void concurrentSameKeyRequestsHaveOnlyOneContentAndOneDraft() throws Exception {
        UserAccount author = fixture(); String token = token(author), key = id();
        var executor = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> pending = new ArrayList<>();
            for (int i = 0; i < 8; i++) pending.add(executor.submit(() -> { start.await(); return create(token, key, "{\"kind\":\"ARTICLE\"}"); }));
            start.countDown(); Set<String> created = new HashSet<>();
            for (var future : pending) {
                var response = future.get(20, TimeUnit.SECONDS);
                if (response.statusCode() == 201) created.add(JsonPath.read(response.body(), "$.data.id"));
                else assertProblem(response, 409, "REQUEST_IN_PROGRESS");
            }
            assertEquals(1, created.size()); assertEquals(1, contentCount(author));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, created.iterator().next()));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test
    void concurrentAuthorsHaveIndependentIdempotencyScopes() throws Exception {
        UserAccount first = fixture(), second = fixture(); String key = id();
        String firstToken = token(first), secondToken = token(second);
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return create(firstToken, key, "{\"kind\":\"ARTICLE\"}"); });
            var b = executor.submit(() -> { start.await(); return create(secondToken, key, "{\"kind\":\"ARTICLE\"}"); });
            start.countDown();
            assertEquals(201, a.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(201, b.get(20, TimeUnit.SECONDS).statusCode());
            assertEquals(1, contentCount(first)); assertEquals(1, contentCount(second));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @Test
    void anExpiredExecutorCannotOverwriteOrReleaseTheRecoveredResult() throws Exception {
        UserAccount author = fixture(); String token = token(author), key = id();
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (calls.getAndIncrement() == 0) { entered.countDown(); assertTrue(proceed.await(15, TimeUnit.SECONDS)); }
            return null;
        }).when(limiter).acquire(author.getId(), "127.0.0.1");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var stale = executor.submit(() -> create(token, key, "{\"kind\":\"ARTICLE\"}"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            jdbc.update("UPDATE content_creation_request SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE created_by=?", author.getId());
            var recovered = create(token, key, "{\"kind\":\"ARTICLE\"}");
            assertEquals(201, recovered.statusCode(), recovered.body());
            proceed.countDown(); assertProblem(stale.get(20, TimeUnit.SECONDS), 409, "REQUEST_IN_PROGRESS");
            assertEquals(1, contentCount(author));
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT state FROM content_creation_request WHERE created_by=?", String.class, author.getId()));
        } finally { proceed.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"REVISION", "POINTER", "RESULT", "ASSET", "TAG"})
    void databaseFailuresRollBackContentDraftReferencesAndTheResult(String stage) throws Exception {
        UserAccount author = fixture(); var input = new LinkedHashMap<String,Object>(); input.put("kind", "ARTICLE");
        var failure = new TransientDataAccessResourceException("sensitive database detail");
        switch (stage) {
            case "REVISION" -> doThrow(failure).when(contents).insertRevision(any(), any());
            case "POINTER" -> doThrow(failure).when(contents).attachWorkingRevision(anyString(), anyString());
            case "RESULT" -> doThrow(failure).when(requests).complete(anyString(), anyString(), anyString(), anyString(), any());
            case "ASSET" -> {
                input.put("coverAssetId", asset(author, "CONTENT_IMAGE", "READY"));
                doThrow(failure).when(contents).insertAsset(anyString(), anyString(), anyString(), anyInt());
            }
            default -> {
                input.put("tagIds", List.of(tag(author, "ACTIVE")));
                doThrow(failure).when(contents).insertTag(anyString(), anyString(), anyInt());
            }
        }
        var response = create(token(author), id(), json.writeValueAsString(input));
        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive database detail"));
        assertEquals(0, contentCount(author));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @Test
    void redisFailureReleasesTheReservationWithoutCreatingContent() throws Exception {
        UserAccount author = fixture();
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-create}:")), any(), any());
        var response = create(token(author), id(), "{\"kind\":\"ARTICLE\"}");
        assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive redis detail"));
        assertEquals(0, contentCount(author));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @Test
    void failedReservationReleaseRemainsDurableForLeaseRecovery() throws Exception {
        UserAccount author = fixture();
        doThrow(new TransientDataAccessResourceException("result unavailable")).when(requests).complete(anyString(), anyString(), anyString(), anyString(), any());
        doThrow(new TransientDataAccessResourceException("release unavailable")).when(requests).release(anyString(), anyString());
        assertProblem(create(token(author), id(), "{\"kind\":\"ARTICLE\"}"), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(0, contentCount(author));
        assertEquals("PROCESSING", jdbc.queryForObject("SELECT state FROM content_creation_request WHERE created_by=?", String.class, author.getId()));
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "WRITE_BLOCKED", "NO_USER"})
    void losingEligibilityAfterReservationStillPreventsTheFinalCommit(String change) throws Exception {
        UserAccount author = fixture();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            switch (change) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", author.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", author.getId());
                case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", author.getId());
                default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, author.getId());
            }
            return null;
        }).when(limiter).acquire(author.getId(), "127.0.0.1");
        var response = create(token(author), id(), "{\"kind\":\"ARTICLE\"}");
        String code = switch (change) {
            case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED";
            case "UNVERIFIED" -> "EMAIL_VERIFICATION_REQUIRED";
            case "NO_USER" -> "PERMISSION_DENIED";
            default -> "ACCOUNT_RESTRICTED";
        };
        assertProblem(response, change.equals("AUTH_VERSION") ? 401 : 403, code);
        assertEquals(0, contentCount(author));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @Test
    void semanticAndHeaderValidationFailuresDoNotConsumeQuotaOrReserveKeys() throws Exception {
        UserAccount author = fixture(); String token = token(author);
        for (String key : List.of("short", "a".repeat(129))) assertProblem(create(token, key, "{\"kind\":\"ARTICLE\"}"), 422, "VALIDATION_ERROR");
        assertProblem(create(token, id(), "{\"kind\":\"ARTICLE\",\"bodyMarkdown\":\"![x](asset:missing)\"}"), 422, "VALIDATION_ERROR");
        assertProblem(create(token, id(), "{\"kind\":\"ARTICLE\",\"tagIds\":[\"tag\",\"tag\"]}"), 422, "VALIDATION_ERROR");
        assertTrue(redis.keys(PREFIX + "{content-create}:*").isEmpty()); assertEquals(0, contentCount(author));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @Test
    void databaseConstraintsPreventCrossContentPointersAndMultipleWorkingDrafts() throws Exception {
        UserAccount author = fixture(); String token = token(author);
        var a = create(token, id(), "{\"kind\":\"ARTICLE\"}"); var b = create(token, id(), "{\"kind\":\"ARTICLE\"}");
        assertEquals(201, a.statusCode()); assertEquals(201, b.statusCode());
        String contentA = JsonPath.read(a.body(), "$.data.id"), revisionA = JsonPath.read(a.body(), "$.data.workingRevision.id");
        String contentB = JsonPath.read(b.body(), "$.data.id");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", revisionA, contentB));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','','',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id(), contentA));
    }

    @Test
    void successfulReplayReturnsTheFirstSnapshotAfterTheDraftChanges() throws Exception {
        UserAccount author = fixture(); String token = token(author), key = id();
        String body = "{\"kind\":\"ARTICLE\",\"title\":\"First draft\"}";
        var first = create(token, key, body); assertEquals(201, first.statusCode(), first.body());
        String revision = JsonPath.read(first.body(), "$.data.workingRevision.id");
        jdbc.update("UPDATE content_revision SET title='Edited draft' WHERE id=?", revision);
        var normalized = json.readValue(body, CreateContentRequest.class);

        var replay = create(token, key, json.writeValueAsString(normalized));

        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String,Object>>read(first.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
        assertEquals(first.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        assertEquals(1, contentCount(author));
    }

    @Test
    void reusedKeysWithDifferentInputsConflictAndExpiredKeysCanCreateAgain() throws Exception {
        UserAccount author = fixture(); String token = token(author), key = id();
        String body = "{\"kind\":\"ARTICLE\"}";
        assertEquals(201, create(token, key, body).statusCode());
        assertProblem(create(token, key, "{\"kind\":\"VIDEO\"}"), 409, "IDEMPOTENCY_KEY_REUSED");
        jdbc.update("UPDATE content_creation_request SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE created_by=?", author.getId());
        var next = create(token, key, body);
        assertEquals(201, next.statusCode(), next.body());
        assertEquals(2, contentCount(author));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void liveReservationsConflictAndExpiredLeasesCanBeRecovered(boolean expired) throws Exception {
        UserAccount author = fixture(); String key = id(), body = "{\"kind\":\"ARTICLE\"}";
        String fingerprint = TokenDigest.sha256(json.writeValueAsString(json.readValue(body, CreateContentRequest.class)));
        jdbc.update("INSERT INTO content_creation_request(id,created_by,key_hash,fingerprint,state,lease_token,lease_until,created_at,expires_at) VALUES(?,?,?,?,'PROCESSING',?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 24 HOUR)",
                id(), author.getId(), TokenDigest.sha256(key), fingerprint, id(), expired ? -1 : 60);

        var response = create(token(author), key, body);

        if (expired) {
            assertEquals(201, response.statusCode(), response.body()); assertEquals(1, contentCount(author));
        } else {
            assertProblem(response, 409, "REQUEST_IN_PROGRESS");
            assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) >= 1);
            assertEquals(0, contentCount(author));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "SOURCE"})
    void successfulReplaysRemainAvailableAfterTheIndependentCreationQuotaIsExhausted(String scope) throws Exception {
        UserAccount author = fixture(); String token = token(author), key = id(), body = "{\"kind\":\"ARTICLE\"}";
        var first = create(token, key, body); assertEquals(201, first.statusCode(), first.body());
        Set<String> counters = redis.keys(PREFIX + "{content-create}:*");
        assertEquals(2, counters.size(), "创建应原子记录来源和用户两个独立计数");
        String counter = PREFIX + "{content-create}:" + (scope.equals("USER") ? "user:" + TokenDigest.sha256(author.getId()) : "source:" + TokenDigest.sha256("127.0.0.1"));
        redis.opsForValue().set(counter, scope.equals("USER") ? "100" : "60", Duration.ofHours(1));

        var replay = create(token, key, body);

        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String,Object>>read(first.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
        assertEquals(scope.equals("USER") ? "100" : "60", redis.opsForValue().get(counter));
        var rejected = create(token, id(), body); assertProblem(rejected, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        assertEquals(1, contentCount(author));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COVER_MISSING", "IMAGE_OTHER", "VIDEO_OTHER", "TAG_MISSING", "COVER_SCENE", "IMAGE_SCENE", "VIDEO_SCENE"})
    void rejectsMissingForeignAndWrongPurposeReferencesWithoutCreatingAnything(String scenario) throws Exception {
        UserAccount author = fixture(), other = fixture();
        var input = new LinkedHashMap<String, Object>(); input.put("kind", scenario.startsWith("VIDEO") ? "VIDEO" : "ARTICLE");
        switch (scenario) {
            case "COVER_MISSING" -> input.put("coverAssetId", id());
            case "IMAGE_OTHER", "IMAGE_SCENE" -> {
                String image = asset(scenario.equals("IMAGE_OTHER") ? other : author,
                        scenario.equals("IMAGE_OTHER") ? "CONTENT_IMAGE" : "AVATAR", "PROCESSING");
                input.put("imageAssetIds", List.of(image)); input.put("bodyMarkdown", "![image](asset:" + image + ")");
            }
            case "VIDEO_OTHER" -> input.put("videoAssetId", asset(other, "CONTENT_VIDEO", "PROCESSING"));
            case "COVER_SCENE" -> input.put("coverAssetId", asset(author, "REPORT_ATTACHMENT", "READY"));
            case "VIDEO_SCENE" -> input.put("videoAssetId", asset(author, "CONTENT_IMAGE", "READY"));
            default -> input.put("tagIds", List.of(id()));
        }
        var response = create(token(author), id(), json.writeValueAsString(input));
        boolean purpose = scenario.endsWith("SCENE");
        assertProblem(response, purpose ? 422 : 404, purpose ? "VALIDATION_ERROR" : "RESOURCE_NOT_FOUND");
        assertEquals(0, contentCount(author));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_creation_request WHERE created_by=?", Integer.class, author.getId()));
    }
    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    private String tag(UserAccount owner, String state) {
        String id = id();
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,?, ?,1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                id, "Content_" + id.substring(0, 20), "", state, owner.getId());
        return id;
    }

    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "content-create-test").accessToken(); }
    private HttpResponse<String> create(String token, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
