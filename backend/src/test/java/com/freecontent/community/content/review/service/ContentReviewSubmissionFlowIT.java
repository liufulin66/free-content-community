package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.mapper.ContentReviewSubmissionMapper;
import com.freecontent.community.content.review.mapper.ContentReviewSubmissionRequestMapper;
import com.freecontent.community.content.support.ContentTestSupport;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.dto.SubmitReviewRequest;
import com.freecontent.community.content.review.ratelimit.ContentReviewSubmissionRateLimiter;
import com.freecontent.community.content.review.service.transaction.ContentReviewSubmissionTransactionService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ContentReviewSubmissionFlowIT {
    private static final String PREFIX = "test:review-submit:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private ContentReviewSubmissionTransactionService transactions;
    @MockitoSpyBean private ContentReviewSubmissionRateLimiter limiter;
    @MockitoSpyBean private ContentReviewSubmissionMapper writes;
    @MockitoSpyBean private ContentReviewSubmissionRequestMapper requests;
    @Autowired private SessionAuthenticationService authentication;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.creation", "content.query", "content.review-submission"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        registry.add("app.content.review-submission.user-hourly-limit", () -> 2);
    }

    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            ContentTestSupport.clearReviews(jdbc, owner);
            jdbc.update("DELETE FROM content_creation_request WHERE created_by=?", owner);
            jdbc.update("UPDATE community_content SET working_revision_id=NULL,published_revision_id=NULL,active_review_id=NULL WHERE author_id=?", owner);
            jdbc.update("DELETE a FROM content_revision_asset a JOIN content_revision r ON r.id=a.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
            jdbc.update("DELETE t FROM content_revision_tag t JOIN content_revision r ON r.id=t.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
            jdbc.update("DELETE r FROM content_revision r JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
            jdbc.update("DELETE FROM community_content WHERE author_id=?", owner);
        }
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test void validArticleCreatesAnAwaitingReview() throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "事务入门", "bodyMarkdown", "数据库事务正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var result = submit(content, token, id(), 1, revision);
        assertEquals(201, result.statusCode(), result.body());
        String review = JsonPath.read(result.body(), "$.data.review.id");
        assertEquals(2, JsonPath.<Integer>read(result.body(), "$.data.contentVersion"));
        assertEquals("INITIAL", JsonPath.read(result.body(), "$.data.review.purpose"));
        assertEquals("PENDING", JsonPath.read(result.body(), "$.data.review.state"));
        assertEquals(1, JsonPath.<Integer>read(result.body(), "$.data.review.version"));
        assertNull(JsonPath.read(result.body(), "$.data.review.decidedAt"));
        assertEquals("/api/v1/creator/contents/" + content + "/reviews/" + review, result.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("IN_REVIEW", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, revision));
        assertEquals(review, jdbc.queryForObject("SELECT active_review_id FROM community_content WHERE id=?", String.class, content));
        assertEquals("UNPUBLISHED", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, content));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE review_id=? AND state='PENDING'", Integer.class, review));
        var frozen = send("PATCH", "/api/v1/creator/contents/" + content + "/draft", token, "{\"expectedVersion\":2,\"title\":\"修改\"}", null);
        assertProblem(frozen, 409, "DRAFT_NOT_EDITABLE");
    }

    @ParameterizedTest @ValueSource(strings = {"TITLE", "BODY", "VIDEO"})
    void incompleteDraftsRemainEditableWithoutCreatingReviewOrEvent(String missing) throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", missing.equals("VIDEO") ? "VIDEO" : "ARTICLE",
                "title", missing.equals("TITLE") ? " \t\u3000" : "标题", "bodyMarkdown", missing.equals("BODY") ? " \n " : "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        assertProblem(submit(content, token, id(), 1, revision), 422, "VALIDATION_ERROR");
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, revision));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE content_id=?", Integer.class, content));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING_UPLOAD", "PROCESSING", "FAILED"})
    void everyReferencedImageMustBeReady(String state) throws Exception {
        var owner = fixture(); String token = token(owner), asset = asset(owner, "CONTENT_IMAGE", state);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "![图](asset:" + asset + ")",
                "coverAssetId", asset, "imageAssetIds", List.of(asset)));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        String key = id(); assertProblem(submit(content, token, key, 1, revision), 409, "MEDIA_NOT_READY");
        jdbc.update("UPDATE media_asset SET state='READY' WHERE id=?", asset);
        assertEquals(201, submit(content, token, key, 1, revision).statusCode());
    }

    @Test void disabledTagsPreventSubmissionUntilEnabled() throws Exception {
        var owner = fixture(); String token = token(owner), tag = id();
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag, tag.substring(0, 20), owner.getId());
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文", "tagIds", List.of(tag)));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id"), key = id();
        assertProblem(submit(content, token, key, 1, revision), 422, "VALIDATION_ERROR");
        jdbc.update("UPDATE community_tag SET status='ACTIVE' WHERE id=?", tag);
        assertEquals(201, submit(content, token, key, 1, revision).statusCode());
    }

    private String asset(UserAccount owner, String scene, String state) {
        String asset = id(); jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,?,'image.png',?,10,?,?,UTC_TIMESTAMP(6))",
                asset, owner.getId(), scene, scene.equals("CONTENT_VIDEO") ? "video/mp4" : "image/png", "private/" + owner.getId() + "/" + asset + "/source", state);
        return asset;
    }

    @Test void sameKeyReplaysTheOriginalResultWithoutAnotherReviewOrEvent() throws Exception {
        var owner = fixture(); String token = token(owner), key = id();
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var first = submit(content, token, key, 1, revision);
        var replay = submit(content, token, key, 1, revision);
        assertEquals(201, first.statusCode(), first.body()); assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String,Object>>read(first.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
        assertEquals(first.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE content_id=?", Integer.class, content));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE content_id=?", Integer.class, content));
        assertProblem(submit(content, token, key, 2, revision), 409, "IDEMPOTENCY_KEY_REUSED");
        var other = create(token, Map.of("kind", "ARTICLE", "title", "第二篇", "bodyMarkdown", "正文"));
        assertProblem(submit(JsonPath.read(other.body(), "$.data.id"), token, key, 1, JsonPath.read(other.body(), "$.data.workingRevision.id")), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test void aLiveReservationReturnsInProgressWithRetryAfter() throws Exception {
        var owner = fixture(); String token = token(owner), key = id();
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        reserve(owner, key, content, revision);
        var result = submit(content, token, key, 1, revision);
        assertProblem(result, 409, "REQUEST_IN_PROGRESS"); assertTrue(Long.parseLong(result.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }
    private String fingerprint(String content, int version, String revision) {
        return TokenDigest.sha256(json.writeValueAsString(List.of(content, version, revision)));
    }
    private void reserve(UserAccount owner, String key, String content, String revision) {
        jdbc.update("INSERT INTO content_review_submission_request(id,created_by,key_hash,fingerprint,state,content_id,lease_token,lease_until,created_at,expires_at) VALUES(?,?,?,?,'PROCESSING',?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 24 HOUR))",
                id(), owner.getId(), TokenDigest.sha256(key), fingerprint(content, 1, revision), content, id());
    }

    @Test void successfulReplaysBypassTheIndependentSubmissionQuota() throws Exception {
        var owner = fixture(); String token = token(owner), key = id();
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        assertEquals(201, submit(content, token, key, 1, revision).statusCode());
        for (int i = 0; i < 3; i++) assertEquals(201, submit(content, token, key, 1, revision).statusCode());
        assertEquals("1", redis.opsForValue().get(PREFIX + "{content-review-submission}:user:" + TokenDigest.sha256(owner.getId())));
        assertProblem(submit(id(), token, id(), 1, id()), 404, "RESOURCE_NOT_FOUND");
        var limited = submit(id(), token, id(), 1, id());
        assertProblem(limited, 429, "RATE_LIMITED"); assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(201, submit(content, token, key, 1, revision).statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"})
    void submittingANewRevisionPreservesTheOldPublishedSnapshotAndVisibility(String publication) throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "公开旧稿", "bodyMarkdown", "旧正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), published = JsonPath.read(created.body(), "$.data.workingRevision.id"), revision = id();
        jdbc.update("UPDATE content_revision SET status='APPROVED' WHERE id=?", published);
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','新稿','新正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content);
        jdbc.update("UPDATE community_content SET working_revision_id=?,published_revision_id=?,publication_status=? WHERE id=?", revision, published, publication, content);
        var before = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", published);
        var result = submit(content, token, id(), 1, revision); assertEquals(201, result.statusCode(), result.body());
        assertEquals(publication.equals("TAKEN_DOWN") ? "REMEDIATION" : "UPDATE", JsonPath.read(result.body(), "$.data.review.purpose"));
        assertEquals(publication, jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, content));
        assertEquals(published, jdbc.queryForObject("SELECT published_revision_id FROM community_content WHERE id=?", String.class, content));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", published));
    }

    @ParameterizedTest @ValueSource(strings = {"IN_REVIEW", "APPROVED", "REJECTED", "CANCELLED", "NONE", "DELETED", "PENDING", "MAX_VERSION"})
    void onlyTheCurrentActiveVersionedDraftCanBeSubmitted(String state) throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id"); int version = 1;
        switch (state) {
            case "NONE" -> { jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?", content); jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?", revision); }
            case "DELETED" -> jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?", content);
            case "PENDING" -> ContentTestSupport.pending(jdbc, content, revision);
            case "MAX_VERSION" -> { version = Integer.MAX_VALUE; jdbc.update("UPDATE community_content SET version=? WHERE id=?", version, content); }
            default -> jdbc.update("UPDATE content_revision SET status=? WHERE id=?", state, revision);
        }
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", content);
        String code = switch (state) { case "DELETED" -> "CONTENT_NOT_EDITABLE"; case "PENDING" -> "REVIEW_ALREADY_PENDING"; case "MAX_VERSION" -> "CONTENT_VERSION_CONFLICT"; default -> "DRAFT_NOT_EDITABLE"; };
        assertProblem(submit(content, token, id(), version, revision), 409, code);
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", content));
    }

    @Test void anUnrelatedOrMissingRevisionDoesNotRevealPrivateData() throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id");
        assertProblem(submit(content, token, id(), 1, id()), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"REVIEW", "FREEZE", "CONTENT", "EVENT", "SNAPSHOT"})
    void failureAtAnyWriteRollsBackReviewStateVersionAndEvent(String stage) throws Exception {
        var owner = fixture(); String token = token(owner), key = id();
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", content);
        var draftBefore = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", revision);
        var failure = new TransientDataAccessResourceException("sensitive database detail");
        switch (stage) {
            case "REVIEW" -> doThrow(failure).when(writes).insertReview(any(), any());
            case "FREEZE" -> doThrow(failure).when(writes).freeze(anyString(), anyString(), any());
            case "CONTENT" -> doThrow(failure).when(writes).attach(anyString(), anyString(), anyInt(), anyString(), anyString(), any());
            case "EVENT" -> doThrow(failure).when(writes).insertEvent(anyString(), any(), anyInt(), anyString(), any());
            default -> doThrow(failure).when(requests).complete(anyString(), anyString(), anyString(), anyString(), any());
        }
        var result = submit(content, token, key, 1, revision); assertProblem(result, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(result.body().contains("sensitive database detail"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", content));
        assertEquals(draftBefore, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", revision));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE content_id=?", Integer.class, content));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE content_id=?", Integer.class, content));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_submission_request WHERE created_by=?", Integer.class, owner.getId()));
        reset(writes, requests); assertEquals(201, submit(content, token, key, 1, revision).statusCode());
    }

    @Test void simultaneousSubmissionsHaveExactlyOneWinnerAndOneVersionConflict() throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { call.callRealMethod(); barrier.await(10, TimeUnit.SECONDS); return null; }).when(limiter).acquire(owner.getId(), "127.0.0.1");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> submit(content, token, id(), 1, revision));
            var second = pool.submit(() -> submit(content, token, id(), 1, revision));
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertEquals(Set.of(201, 409), Set.of(a.statusCode(), b.statusCode()));
            assertProblem(a.statusCode() == 409 ? a : b, 409, "CONTENT_VERSION_CONFLICT");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE content_id=?", Integer.class, content));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE content_id=?", Integer.class, content));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "NO_USER", "WRITE_BLOCKED", "DISABLED"})
    void finalTransactionRechecksEligibilityAfterReservation(String change) throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        doAnswer(call -> { call.callRealMethod(); changeAccount(owner, change); return null; }).when(limiter).acquire(owner.getId(), "127.0.0.1");
        var result = submit(content, token, id(), 1, revision);
        assertProblem(result, change.equals("AUTH_VERSION") ? 401 : 403, eligibilityError(change));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, revision));
    }

    @Test void expiredLeasesCanBeClaimedButStaleExecutorsCannotWriteOrReleaseThem() throws Exception {
        var owner = fixture(); String token = token(owner), key = id(); SessionPrincipal principal = authentication.authenticate(token);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var input = json.readValue("{\"expectedVersion\":1,\"revisionId\":\"" + revision + "\"}", SubmitReviewRequest.class);
        var first = transactions.claim(content, input, TokenDigest.sha256(key), fingerprint(content, 1, revision), principal);
        jdbc.update("UPDATE content_review_submission_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", first.request().getId());
        var second = transactions.claim(content, input, TokenDigest.sha256(key), fingerprint(content, 1, revision), principal);
        assertEquals(ErrorCode.REQUEST_IN_PROGRESS, assertThrows(BusinessException.class, () -> transactions.finish(first, principal)).errorCode());
        transactions.release(first);
        assertEquals(second.request().getLeaseToken(), jdbc.queryForObject("SELECT lease_token FROM content_review_submission_request WHERE id=?", String.class, first.request().getId()));
        assertEquals(2, transactions.finish(second, principal).contentVersion());
    }

    private void changeAccount(UserAccount owner, String change) {
        switch (change) {
            case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", owner.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", owner.getId());
            case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner.getId());
            default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, owner.getId());
        }
    }
    private String eligibilityError(String change) {
        return switch (change) { case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED"; case "UNVERIFIED", "NO_USER" -> "PERMISSION_DENIED"; default -> "ACCOUNT_RESTRICTED"; };
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "MODERATOR", "ADMIN"})
    void noRoleCanSubmitAnotherAuthorsContentOrLearnItsVersion(String role) throws Exception {
        var owner = fixture(); var other = fixture();
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", other.getId(), role);
        var created = create(token(owner), Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var result = submit(content, token(other), id(), 99, revision); assertProblem(result, 404, "RESOURCE_NOT_FOUND");
        assertFalse(JsonPath.<Map<String,Object>>read(result.body(), "$").containsKey("currentVersion"));
    }

    @Test void videoWithAReadyVideoCanBeSubmittedWithoutArticleBody() throws Exception {
        var owner = fixture(); String token = token(owner), video = asset(owner, "CONTENT_VIDEO", "READY");
        var created = create(token, Map.of("kind", "VIDEO", "title", "视频标题", "videoAssetId", video));
        var result = submit(JsonPath.read(created.body(), "$.data.id"), token, id(), 1, JsonPath.read(created.body(), "$.data.workingRevision.id"));
        assertEquals(201, result.statusCode(), result.body());
    }

    @ParameterizedTest @ValueSource(strings = {"OWNER", "SCENE"})
    void referenceOwnershipAndSceneAreRevalidatedAtSubmission(String change) throws Exception {
        var owner = fixture(); var other = fixture(); String token = token(owner), image = asset(owner, "CONTENT_IMAGE", "READY");
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文", "coverAssetId", image));
        if (change.equals("OWNER")) jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?", other.getId(), image);
        else jdbc.update("UPDATE media_asset SET scene='AVATAR' WHERE id=?", image);
        var result = submit(JsonPath.read(created.body(), "$.data.id"), token, id(), 1, JsonPath.read(created.body(), "$.data.workingRevision.id"));
        assertProblem(result, change.equals("OWNER") ? 404 : 422, change.equals("OWNER") ? "RESOURCE_NOT_FOUND" : "VALIDATION_ERROR");
    }

    @Test void aSaveBetweenReservationAndSubmissionIsNeverOverwritten() throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        doAnswer(call -> {
            call.callRealMethod(); var saved = send("PATCH", "/api/v1/creator/contents/" + content + "/draft", token, "{\"expectedVersion\":1,\"title\":\"并发保存\"}", null);
            assertEquals(200, saved.statusCode(), saved.body()); return null;
        }).when(limiter).acquire(owner.getId(), "127.0.0.1");
        var result = submit(content, token, id(), 1, revision); assertProblem(result, 409, "CONTENT_VERSION_CONFLICT");
        assertEquals(2, JsonPath.<Integer>read(result.body(), "$.currentVersion"));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, revision));
        assertEquals("并发保存", jdbc.queryForObject("SELECT title FROM content_revision WHERE id=?", String.class, revision));
    }

    @Test void replayReturnsTheFirstSnapshotAfterReviewTerminationButStillChecksCurrentEligibility() throws Exception {
        var owner = fixture(); String token = token(owner), key = id();
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var first = submit(content, token, key, 1, revision); assertEquals(201, first.statusCode());
        jdbc.update("UPDATE community_content SET active_review_id=NULL,working_revision_id=NULL,version=3 WHERE id=?", content);
        jdbc.update("UPDATE content_review SET state='CANCELLED',version=2 WHERE content_id=?", content);
        jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?", revision);
        var replay = submit(content, token, key, 1, revision); assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String,Object>>read(first.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
        changeAccount(owner, "WRITE_BLOCKED"); assertProblem(submit(content, token, key, 1, revision), 403, "ACCOUNT_RESTRICTED");
    }

    @Test void redisOutageReleasesTheReservationAndLeavesTheDraftUnchanged() throws Exception {
        var owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-review-submission}:")), any(), any());
        var result = submit(content, token, id(), 1, revision); assertProblem(result, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(result.body().contains("sensitive redis detail"));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, revision));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_submission_request WHERE created_by=?", Integer.class, owner.getId()));
    }

    @Test void simultaneousSameKeyRequestsReportProgressAndThenReplayOneSubmission() throws Exception {
        var owner = fixture(); String token = token(owner), key = id();
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var reached = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        doAnswer(call -> { call.callRealMethod(); reached.countDown(); assertTrue(proceed.await(10, TimeUnit.SECONDS)); return null; }).when(limiter).acquire(owner.getId(), "127.0.0.1");
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> submit(content, token, key, 1, revision)); assertTrue(reached.await(10, TimeUnit.SECONDS));
            assertProblem(submit(content, token, key, 1, revision), 409, "REQUEST_IN_PROGRESS"); proceed.countDown();
            var result = first.get(20, TimeUnit.SECONDS); assertEquals(201, result.statusCode(), result.body());
            var replay = submit(content, token, key, 1, revision); assertEquals(201, replay.statusCode());
            assertEquals(JsonPath.<Map<String,Object>>read(result.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
            verify(limiter, times(1)).acquire(owner.getId(), "127.0.0.1");
        } finally { proceed.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void aFullyExpiredReservationAllowsANewAttemptWithTheSameKey() throws Exception {
        var owner = fixture(); String token = token(owner), key = id();
        var created = create(token, Map.of("kind", "ARTICLE", "title", "标题", "bodyMarkdown", "正文"));
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        reserve(owner, key, content, revision);
        jdbc.update("UPDATE content_review_submission_request SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?", owner.getId());
        assertEquals(201, submit(content, token, key, 1, revision).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_submission_request WHERE created_by=?", Integer.class, owner.getId()));
    }

    private UserAccount fixture() {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("review-submit-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        owners.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt()); return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "review-submit-test").accessToken(); }
    private HttpResponse<String> create(String token, Map<String,Object> input) throws Exception {
        var response = send("POST", "/api/v1/creator/contents", token, json.writeValueAsString(input), id());
        assertEquals(201, response.statusCode(), response.body()); return response;
    }
    private HttpResponse<String> submit(String content, String token, String key, int version, String revision) throws Exception {
        return send("POST", "/api/v1/creator/contents/" + content + "/reviews", token,
                json.writeValueAsString(Map.of("expectedVersion", version, "revisionId", revision)), key);
    }
    private HttpResponse<String> send(String method, String path, String token, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
