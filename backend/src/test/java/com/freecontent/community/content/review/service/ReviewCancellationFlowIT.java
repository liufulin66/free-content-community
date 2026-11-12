package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.dto.CancelReviewRequest;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.content.review.mapper.ReviewCancellationMapper;
import com.freecontent.community.content.review.ratelimit.ReviewCancellationRateLimiter;
import com.freecontent.community.content.review.ratelimit.ReviewDecisionRateLimiter;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ReviewCancellationFlowIT {
    private static final String PREFIX = "test:review-cancellation:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private ReviewCancellationMapper writes;
    @MockitoSpyBean private ReviewCancellationRateLimiter limiter;
    @MockitoSpyBean private ReviewDecisionRateLimiter decisionLimiter;
    private final List<String> actors = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.review-cancellation", "content.review-decision", "content.query"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        registry.add("app.content.review-cancellation.user-per-minute-limit", () -> 3);
    }
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String actor : actors) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='content_review_cancellation_audit'", Integer.class) == 1)
                jdbc.update("DELETE FROM content_review_cancellation_audit WHERE actor_id=? OR content_id IN (SELECT id FROM community_content WHERE author_id=?)", actor, actor);
            jdbc.update("DELETE FROM content_review_decision_request WHERE created_by=? OR content_id IN (SELECT id FROM community_content WHERE author_id=?)", actor, actor);
            jdbc.update("DELETE FROM content_review_decision_audit WHERE actor_id=? OR content_id IN (SELECT id FROM community_content WHERE author_id=?)", actor, actor);
        }
        for (String actor : actors) ContentTestSupport.clearReviews(jdbc, actor);
        for (String actor : actors) {
            ContentTestSupport.clearWorkspace(jdbc, actor);
        }
        for (String actor : actors) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", actor); jdbc.update("DELETE FROM community_tag WHERE created_by=?", actor);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", actor); users.deleteById(actor);
        }
    }

    @Test void ownerCanCancelTheCurrentPendingReviewWithoutAnIdempotencyHeader() throws Exception {
        var owner = fixture("USER"); var seed = review(owner);
        var response = cancel(seed.content(), seed.review(), token(owner), 2, 1);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("CANCELLED", JsonPath.read(response.body(), "$.data.review.state"));
        assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.review.version")); assertEquals(3, JsonPath.<Integer>read(response.body(), "$.data.contentVersion"));
        assertNull(JsonPath.read(response.body(), "$.data.review.reviewerId")); assertNull(JsonPath.read(response.body(), "$.data.review.reason"));
        var content = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertNull(content.get("working_revision_id")); assertNull(content.get("active_review_id")); assertEquals("UNPUBLISHED", content.get("publication_status"));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, seed.revision()));
        assertEquals("冻结标题", jdbc.queryForObject("SELECT title FROM content_revision WHERE id=?", String.class, seed.revision()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_cancellation_audit WHERE review_id=?", Integer.class, seed.review()));
        var audit = jdbc.queryForMap("SELECT * FROM content_review_cancellation_audit WHERE review_id=?", seed.review());
        assertEquals(owner.getId(), audit.get("actor_id")); assertEquals(JsonPath.read(response.body(), "$.requestId"), audit.get("request_id"));
        assertEquals(2, audit.get("before_content_version")); assertEquals(3, audit.get("after_content_version"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE review_id=? AND event_type='CONTENT_REVIEW_CANCELLED'", Integer.class, seed.review()));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test void ownCancelledReviewCanBeRetriedWithOldVersionsWithoutTouchingANewDraft() throws Exception {
        var owner = fixture("USER"); var seed = review(owner); String token = token(owner);
        assertEquals(200, cancel(seed.content(), seed.review(), token, 2, 1).statusCode());
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var repeated = cancel(seed.content(), seed.review(), token, 2, 1); assertEquals(200, repeated.statusCode(), repeated.body());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        String next = id();
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','新工作稿','新正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", next, seed.content());
        jdbc.update("UPDATE community_content SET working_revision_id=?,version=6 WHERE id=?", next, seed.content());
        var current = cancel(seed.content(), seed.review(), token, 2, 1); assertEquals(200, current.statusCode(), current.body());
        assertEquals(6, JsonPath.<Integer>read(current.body(), "$.data.contentVersion"));
        assertEquals(next, jdbc.queryForObject("SELECT working_revision_id FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_cancellation_audit WHERE review_id=?", Integer.class, seed.review()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE review_id=? AND event_type='CONTENT_REVIEW_CANCELLED'", Integer.class, seed.review()));
    }

    @ParameterizedTest @ValueSource(strings = {"CONTENT", "REVIEW", "MAX_CONTENT", "MAX_REVIEW"})
    void firstCancellationChecksBothVersionsAndPreventsOverflow(String mismatch) throws Exception {
        var owner = fixture("USER"); var seed = review(owner); int content = 2, review = 1;
        switch (mismatch) {
            case "CONTENT" -> content = 1;
            case "REVIEW" -> review = 2;
            case "MAX_CONTENT" -> { content = Integer.MAX_VALUE; jdbc.update("UPDATE community_content SET version=? WHERE id=?", content, seed.content()); }
            default -> { review = Integer.MAX_VALUE; jdbc.update("UPDATE content_review SET version=? WHERE id=?", review, seed.review()); }
        }
        var response = cancel(seed.content(), seed.review(), token(owner), content, review);
        assertProblem(response, 409, mismatch.endsWith("CONTENT") ? "CONTENT_VERSION_CONFLICT" : "REVIEW_VERSION_CONFLICT");
        assertEquals(mismatch.startsWith("MAX") ? Integer.MAX_VALUE : mismatch.equals("CONTENT") ? 2 : 1, JsonPath.<Integer>read(response.body(), "$.currentVersion"));
        assertEquals(0, auditCount(seed));
    }

    @ParameterizedTest @ValueSource(strings = {"APPROVED", "REJECTED", "INVALIDATED", "CANCELLED", "DELETED", "NO_ACTIVE", "NO_WORKING", "REVISION_TERMINAL"})
    void otherTerminalStatesAndUnprovenCancellationCannotBecomeSuccessfulRetries(String state) throws Exception {
        var owner = fixture("USER"); var seed = review(owner);
        switch (state) {
            case "DELETED" -> jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?", seed.content());
            case "NO_ACTIVE" -> jdbc.update("UPDATE community_content SET active_review_id=NULL WHERE id=?", seed.content());
            case "NO_WORKING" -> jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?", seed.content());
            case "REVISION_TERMINAL" -> jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?", seed.revision());
            default -> jdbc.update("UPDATE content_review SET state=? WHERE id=?", state, seed.review());
        }
        var before = businessRows(seed);
        assertProblem(cancel(seed.content(), seed.review(), token(owner), 2, 1), 409, "REVIEW_NOT_PENDING"); assertEquals(before, businessRows(seed));
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "MODERATOR", "ADMIN"})
    void otherUsersAndAdministrativeRolesCannotCancelAnotherAuthorsReview(String role) throws Exception {
        var owner = fixture("USER"); var other = fixture(role); var seed = review(owner);
        var response = cancel(seed.content(), seed.review(), token(other), 99, 99); assertProblem(response, 404, "RESOURCE_NOT_FOUND");
        assertFalse(JsonPath.<Map<String,Object>>read(response.body(), "$").containsKey("currentVersion")); assertEquals(0, auditCount(seed));
    }

    @ParameterizedTest @ValueSource(strings = {"CONTENT", "REVIEW", "PAIR"})
    void bothPathIdsMustResolveToTheSameOwnedContent(String mismatch) throws Exception {
        var owner = fixture("USER"); var a = review(owner); var b = review(owner);
        String content = mismatch.equals("CONTENT") ? id() : a.content(), review = mismatch.equals("REVIEW") ? id() : mismatch.equals("PAIR") ? b.review() : a.review();
        assertProblem(cancel(content, review, token(owner), 2, 1), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"UNPUBLISHED", "PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"})
    void cancellationNeverChangesThePreviousPublicSnapshotOrPublicationTimes(String publication) throws Exception {
        var owner = fixture("USER"); var seed = review(owner);
        if (!publication.equals("UNPUBLISHED")) {
            String published = id(); jdbc.update("UPDATE content_revision SET revision_number=2 WHERE id=?", seed.revision());
            jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'APPROVED','旧公开稿','旧正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", published, seed.content());
            LocalDateTime first = LocalDateTime.of(2026,1,1,0,0);
            jdbc.update("UPDATE community_content SET publication_status=?,published_revision_id=?,first_published_at=?,published_at=? WHERE id=?", publication, published, first, first.plusDays(1), seed.content());
            jdbc.update("UPDATE content_review SET purpose=? WHERE id=?", publication.equals("TAKEN_DOWN") ? "REMEDIATION" : "UPDATE", seed.review());
        }
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertEquals(200, cancel(seed.content(), seed.review(), token(owner), 2, 1).statusCode());
        var after = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        for (String field : List.of("publication_status", "published_revision_id", "first_published_at", "published_at")) assertEquals(before.get(field), after.get(field));
    }

    @Test void unreadyAssetsDisabledTagsAndFrozenBodyArePreserved() throws Exception {
        var owner = fixture("USER"); var seed = review(owner); String asset = id(), tag = id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,'CONTENT_IMAGE','image.png','image/png',1,?,'FAILED',UTC_TIMESTAMP(6))", asset, owner.getId(), "private/" + owner.getId() + "/" + asset + "/source");
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag, tag.substring(0,20), owner.getId());
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", seed.revision(), asset);
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", seed.revision(), tag);
        var assets = jdbc.queryForList("SELECT * FROM content_revision_asset WHERE revision_id=?", seed.revision());
        var tags = jdbc.queryForList("SELECT * FROM content_revision_tag WHERE revision_id=?", seed.revision());
        assertEquals(200, cancel(seed.content(), seed.review(), token(owner), 2, 1).statusCode());
        assertEquals(assets, jdbc.queryForList("SELECT * FROM content_revision_asset WHERE revision_id=?", seed.revision()));
        assertEquals(tags, jdbc.queryForList("SELECT * FROM content_revision_tag WHERE revision_id=?", seed.revision()));
        assertEquals("冻结正文", jdbc.queryForObject("SELECT body_markdown FROM content_revision WHERE id=?", String.class, seed.revision()));
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM media_asset WHERE id=?", String.class, asset));
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "NO_USER", "WRITE_BLOCKED", "DISABLED"})
    void currentEligibilityIsCheckedAfterCountingAndBeforeMutation(String change) throws Exception {
        var owner = fixture("USER"); var seed = review(owner);
        doAnswer(call -> { call.callRealMethod(); changeAccount(owner, change); return null; }).when(limiter).acquire(owner.getId(), "127.0.0.1");
        var response = cancel(seed.content(), seed.review(), token(owner), 2, 1);
        assertProblem(response, change.equals("AUTH_VERSION") ? 401 : 403,
                switch (change) { case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED"; case "UNVERIFIED", "NO_USER" -> "PERMISSION_DENIED"; default -> "ACCOUNT_RESTRICTED"; });
        assertEquals(0, auditCount(seed)); assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM content_review WHERE id=?", String.class, seed.review()));
    }

    @ParameterizedTest @ValueSource(strings = {"REVISION", "REVIEW", "CONTENT", "AUDIT", "ZERO_AUDIT", "EVENT"})
    void anyWriteFailureRollsBackStatePointersAuditAndEvent(String stage) throws Exception {
        var owner = fixture("USER"); var seed = review(owner); String token = token(owner); var before = businessRows(seed);
        var failure = new TransientDataAccessResourceException("sensitive database detail");
        switch (stage) {
            case "REVISION" -> doThrow(failure).when(writes).cancelRevision(anyString(), anyString(), any());
            case "REVIEW" -> doThrow(failure).when(writes).cancelReview(anyString(), anyString(), anyString(), anyInt(), any());
            case "CONTENT" -> doThrow(failure).when(writes).updateContent(anyString(), anyString(), anyInt(), anyString(), anyString(), any());
            case "AUDIT" -> doThrow(failure).when(writes).insertAudit(any());
            case "ZERO_AUDIT" -> doReturn(0).when(writes).insertAudit(any());
            default -> doThrow(failure).when(writes).insertEvent(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), any());
        }
        var response = cancel(seed.content(), seed.review(), token, 2, 1); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive")); assertEquals(before, businessRows(seed));
        reset(writes); assertEquals(200, cancel(seed.content(), seed.review(), token, 2, 1).statusCode());
    }

    @Test void parallelOwnerRetriesCancelOnceAndBothReturnSuccess() throws Exception {
        var owner = fixture("USER"); var seed = review(owner); String token = token(owner); var barrier = new CyclicBarrier(2);
        doAnswer(call -> { call.callRealMethod(); barrier.await(10, TimeUnit.SECONDS); return null; }).when(limiter).acquire(owner.getId(), "127.0.0.1");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> cancel(seed.content(), seed.review(), token, 2, 1)); var b = pool.submit(() -> cancel(seed.content(), seed.review(), token, 2, 1));
            var first = a.get(20, TimeUnit.SECONDS); var second = b.get(20, TimeUnit.SECONDS);
            assertEquals(200, first.statusCode(), first.body()); assertEquals(200, second.statusCode(), second.body());
            assertEquals(JsonPath.<Map<String,Object>>read(first.body(), "$.data"), JsonPath.read(second.body(), "$.data"));
            assertEquals(1, auditCount(seed)); assertEquals(1, cancellationEvents(seed));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"APPROVE", "REJECT"})
    void cancellationAndModeratorDecisionHaveOnlyOneStateChangeWinner(String decision) throws Exception {
        var owner = fixture("USER"); var actor = fixture("MODERATOR"); var seed = review(owner); String ownerToken = token(owner), actorToken = token(actor);
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { call.callRealMethod(); barrier.await(10, TimeUnit.SECONDS); return null; }).when(limiter).acquire(owner.getId(), "127.0.0.1");
        doAnswer(call -> { call.callRealMethod(); barrier.await(10, TimeUnit.SECONDS); return null; }).when(decisionLimiter).acquire(actor.getId(), "127.0.0.1");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> cancel(seed.content(), seed.review(), ownerToken, 2, 1));
            var b = pool.submit(() -> send("POST", "/api/v1/admin/reviews/" + seed.review() + "/decisions", actorToken,
                    json.writeValueAsString(Map.of("expectedContentVersion",2,"expectedReviewVersion",1,"decision",decision,"reason","人工审批")), id()));
            var cancelled = a.get(20, TimeUnit.SECONDS); var decided = b.get(20, TimeUnit.SECONDS);
            assertEquals(Set.of(200,409), Set.of(cancelled.statusCode(), decided.statusCode()));
            assertProblem(cancelled.statusCode() == 409 ? cancelled : decided, 409, "CONTENT_VERSION_CONFLICT");
            assertEquals(3, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
            int decisions = jdbc.queryForObject("SELECT COUNT(*) FROM content_review_decision_audit WHERE review_id=?", Integer.class, seed.review());
            assertEquals(1, decisions + auditCount(seed)); assertEquals(cancelled.statusCode() == 200 ? 1 : 0, cancellationEvents(seed));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void repeatedSuccessNotFoundAndConflictRequestsConsumeIndependentQuota() throws Exception {
        var owner = fixture("USER"); var seed = review(owner); String token = token(owner);
        assertProblem(cancel(seed.content(), id(), token, 2, 1), 404, "RESOURCE_NOT_FOUND");
        assertProblem(cancel(seed.content(), seed.review(), token, 1, 1), 409, "CONTENT_VERSION_CONFLICT");
        assertEquals(200, cancel(seed.content(), seed.review(), token, 2, 1).statusCode());
        var repeated = cancel(seed.content(), seed.review(), token, 2, 1); assertProblem(repeated, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(repeated.headers().firstValue("Retry-After").orElseThrow()) > 0); assertEquals(1, auditCount(seed));
        assertTrue(redis.keys(PREFIX + "{content-query}:*").isEmpty()); assertTrue(redis.keys(PREFIX + "{content-review-decision}:*").isEmpty());
    }

    @Test void sourceLimitAlsoRejectsAFreshOwnerWithoutMutation() throws Exception {
        var owner = fixture("USER"); var seed = review(owner);
        redis.opsForValue().set(PREFIX + "{content-review-cancellation}:source:" + TokenDigest.sha256("127.0.0.1"), "120", Duration.ofMinutes(1));
        assertProblem(cancel(seed.content(), seed.review(), token(owner), 2, 1), 429, "RATE_LIMITED"); verifyNoInteractions(writes);
    }

    @Test void redisFailureStopsBeforeOwnedContentAccess() throws Exception {
        var owner = fixture("USER"); var seed = review(owner);
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-review-cancellation}:")), any(), any());
        var response = cancel(seed.content(), seed.review(), token(owner), 2, 1); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive")); verifyNoInteractions(writes);
    }

    @Test void swaggerDefinesDualVersionsAndNoIdempotencyHeader() throws Exception {
        var response = send("GET", "/v3/api-docs", null, null, null); assertEquals(200, response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}/reviews/{reviewId}/cancellation'].put");
        assertEquals("cancelMyReview", operation.get("operationId"));
        List<Map<String,Object>> parameters = (List<Map<String,Object>>)operation.get("parameters");
        assertEquals(Set.of("contentId","reviewId"),parameters.stream().map(p -> (String)p.get("name")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(parameters.stream().allMatch(p -> p.get("in").equals("path")));
        Map<String,Object> schema = JsonPath.read(response.body(), "$.components.schemas.CancelReviewRequest");
        assertEquals(Set.of("expectedVersion","expectedReviewVersion"),new HashSet<>((List<?>)schema.get("required"))); assertEquals(false,schema.get("additionalProperties"));
        Map<String,Object> fields = (Map<String,Object>)schema.get("properties");
        for (String field : List.of("expectedVersion","expectedReviewVersion")) {
            var version = (Map<?,?>)fields.get(field); assertEquals("integer",version.get("type")); assertEquals(Integer.MAX_VALUE,((Number)version.get("maximum")).intValue());
        }
    }

    private int auditCount(Seed seed) { return jdbc.queryForObject("SELECT COUNT(*) FROM content_review_cancellation_audit WHERE review_id=?", Integer.class, seed.review()); }
    private int cancellationEvents(Seed seed) { return jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE review_id=? AND event_type='CONTENT_REVIEW_CANCELLED'", Integer.class, seed.review()); }
    private List<Object> businessRows(Seed seed) {
        return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()), jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", seed.review()),
                jdbc.queryForList("SELECT * FROM content_review_cancellation_audit WHERE review_id=?", seed.review()),
                jdbc.queryForList("SELECT * FROM content_review_outbox WHERE review_id=? ORDER BY id", seed.review()));
    }
    private void changeAccount(UserAccount owner, String change) {
        switch (change) {
            case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", owner.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", owner.getId());
            case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner.getId());
            default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, owner.getId());
        }
    }
    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private record Seed(String content, String revision, String review) { }
    private Seed review(UserAccount owner) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, owner.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'IN_REVIEW','冻结标题','冻结正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content);
        String review = ContentTestSupport.pending(jdbc, content, revision);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", revision, review, content);
        return new Seed(content, revision, review);
    }
    private UserAccount fixture(String role) {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("review-cancellation-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        actors.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", user.getId(), role);
        return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "review-cancellation-test").accessToken(); }
    private HttpResponse<String> cancel(String content, String review, String token, int contentVersion, int reviewVersion) throws Exception {
        return send("PUT", "/api/v1/creator/contents/" + content + "/reviews/" + review + "/cancellation", token,
                json.writeValueAsString(Map.of("expectedVersion", contentVersion, "expectedReviewVersion", reviewVersion)), null);
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
