package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.mapper.ReviewDecisionMapper;
import com.freecontent.community.content.review.mapper.ReviewDecisionRequestMapper;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.content.review.dto.ReviewDecisionRequest;
import com.freecontent.community.content.review.ratelimit.ReviewDecisionRateLimiter;
import com.freecontent.community.content.review.service.transaction.ReviewDecisionTransactionService;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
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
class ReviewDecisionFlowIT {
    private static final String PREFIX = "test:review-decision:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private ReviewDecisionMapper writes;
    @MockitoSpyBean private ReviewDecisionRequestMapper requests;
    @MockitoSpyBean private ReviewDecisionRateLimiter limiter;
    @Autowired private ReviewDecisionTransactionService transactions;
    @Autowired private SessionAuthenticationService authentication;
    private final List<String> actors = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.review-decision", "content.admin-review-query"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        registry.add("app.content.review-decision.user-hourly-limit", () -> 3);
    }
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String actor : actors) {
            for (String table : List.of("content_review_decision_request", "content_review_decision_audit"))
                if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table) == 1)
                    jdbc.update("DELETE FROM " + table + " WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?) OR "
                            + (table.endsWith("_audit") ? "actor_id" : "created_by") + "=?", actor, actor);
        }
        for (String actor : actors) ContentTestSupport.clearReviews(jdbc, actor);
        for (String actor : actors) {
            ContentTestSupport.clearWorkspace(jdbc, actor);
        }
        for (String actor : actors) {
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", actor); jdbc.update("DELETE FROM media_asset WHERE owner_id=?", actor);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", actor); users.deleteById(actor);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"APPROVE", "REJECT"})
    void pendingCurrentRevisionCanBeApprovedOrRejected(String decision) throws Exception {
        var moderator = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author);
        var response = decide(seed.review(), token(moderator), id(), 2, 1, decision, "人工核对反馈");
        assertEquals(200, response.statusCode(), response.body());
        String state = decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
        assertEquals(state, JsonPath.read(response.body(), "$.data.review.state"));
        assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.review.version")); assertEquals(3, JsonPath.<Integer>read(response.body(), "$.data.contentVersion"));
        assertEquals(moderator.getId(), JsonPath.read(response.body(), "$.data.review.reviewerId"));
        assertEquals("人工核对反馈", JsonPath.read(response.body(), "$.data.review.reason"));
        var content = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        assertNull(content.get("working_revision_id")); assertNull(content.get("active_review_id"));
        assertEquals(decision.equals("APPROVE") ? "PUBLISHED" : "UNPUBLISHED", content.get("publication_status"));
        assertEquals(decision.equals("APPROVE") ? seed.revision() : null, content.get("published_revision_id"));
        if (decision.equals("APPROVE")) { assertNotNull(content.get("first_published_at")); assertEquals(content.get("first_published_at"), content.get("published_at")); }
        assertEquals("冻结标题", jdbc.queryForObject("SELECT title FROM content_revision WHERE id=?", String.class, seed.revision()));
        assertEquals(state, jdbc.queryForObject("SELECT status FROM content_revision WHERE id=?", String.class, seed.revision()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_decision_audit WHERE review_id=?", Integer.class, seed.review()));
        var audit = jdbc.queryForMap("SELECT * FROM content_review_decision_audit WHERE review_id=?", seed.review());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), audit.get("request_id"));
        assertEquals(2, audit.get("before_content_version")); assertEquals(3, audit.get("after_content_version"));
        assertEquals(1, audit.get("before_review_version")); assertEquals(2, audit.get("after_review_version"));
        assertEquals(decision.equals("APPROVE") ? 2 : 1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE review_id=?", Integer.class, seed.review()));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test void sameKeyReturnsTheFirstDecisionWithoutAnotherStateChangeAuditOrEvent() throws Exception {
        var moderator = fixture("MODERATOR"); var seed = review(fixture("USER")); String token = token(moderator), key = id();
        var first = decide(seed.review(), token, key, 2, 1, "APPROVE", "通过"); assertEquals(200, first.statusCode(), first.body());
        var replay = decide(seed.review(), token, key, 2, 1, "APPROVE", "通过");
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(JsonPath.<Map<String,Object>>read(first.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
        assertEquals(3, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_decision_audit WHERE review_id=?", Integer.class, seed.review()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE review_id=?", Integer.class, seed.review()));
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING_UPLOAD", "PROCESSING", "FAILED"})
    void approvalRequiresReadyMediaButRejectionRemainsPossible(String state) throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author); String asset = asset(author, state);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", seed.revision(), asset);
        String token = token(actor); var rejectedApproval = decide(seed.review(), token, id(), 2, 1, "APPROVE", "申请通过");
        assertEquals(409, rejectedApproval.statusCode(), rejectedApproval.body()); assertEquals("MEDIA_NOT_READY", JsonPath.read(rejectedApproval.body(), "$.code"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM content_review WHERE id=?", String.class, seed.review()));
        assertEquals(200, decide(seed.review(), token, id(), 2, 1, "REJECT", "素材未通过校验").statusCode());
    }

    @Test void disabledTagsDoNotInvalidateAnAlreadySubmittedSnapshot() throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author); String tag = id();
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag, tag.substring(0,20), actor.getId());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", seed.revision(), tag);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", seed.revision(), asset(author, "READY"));
        assertEquals(200, decide(seed.review(), token(actor), id(), 2, 1, "APPROVE", "依据已提交的标签快照通过").statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"OWNER", "SCENE"})
    void approvalRechecksTheFrozenAssetsOwnerAndScene(String change) throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author); String asset = asset(author, "READY");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", seed.revision(), asset);
        if (change.equals("OWNER")) jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?", actor.getId(), asset);
        else jdbc.update("UPDATE media_asset SET scene='AVATAR' WHERE id=?", asset);
        assertProblem(decide(seed.review(), token(actor), id(), 2, 1, "APPROVE", "引用复核"), change.equals("OWNER") ? 404 : 422,
                change.equals("OWNER") ? "RESOURCE_NOT_FOUND" : "VALIDATION_ERROR"); assertEquals(0, auditCount(seed));
    }

    private String asset(UserAccount author, String state) {
        String asset = id(); jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,'CONTENT_IMAGE','image.png','image/png',1,?,?,UTC_TIMESTAMP(6))",
                asset, author.getId(), "private/" + author.getId() + "/" + asset + "/source", state); return asset;
    }

    @ParameterizedTest @CsvSource({"PUBLISHED,APPROVE", "PUBLISHED,REJECT", "WITHDRAWN,APPROVE", "WITHDRAWN,REJECT", "TAKEN_DOWN,APPROVE", "TAKEN_DOWN,REJECT"})
    void updatesAndRemediationPreserveHistoryAndOnlyApprovalChangesThePublicPointer(String publication, String decision) throws Exception {
        var actor = fixture("ADMIN"); var seed = review(fixture("USER")); String old = publishedHistory(seed, publication);
        var oldSnapshot = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", old);
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var result = decide(seed.review(), token(actor), id(), 2, 1, decision, "更新审核反馈"); assertEquals(200, result.statusCode(), result.body());
        var after = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()); boolean approved = decision.equals("APPROVE");
        assertEquals(approved ? "PUBLISHED" : publication, after.get("publication_status"));
        assertEquals(approved ? seed.revision() : old, after.get("published_revision_id"));
        assertEquals(before.get("first_published_at"), after.get("first_published_at"));
        if (approved) assertNotEquals(before.get("published_at"), after.get("published_at")); else assertEquals(before.get("published_at"), after.get("published_at"));
        assertEquals(oldSnapshot, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", old));
        assertNull(after.get("working_revision_id")); assertNull(after.get("active_review_id"));
    }

    @Test void ordinaryPendingUpdateCannotReleaseTakenDownContent() throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); publishedHistory(seed, "TAKEN_DOWN");
        jdbc.update("UPDATE content_review SET purpose='UPDATE' WHERE id=?", seed.review());
        assertProblem(decide(seed.review(), token(actor), id(), 2, 1, "APPROVE", "普通稿尝试通过"), 409, "CONTENT_NOT_EDITABLE");
        assertEquals("TAKEN_DOWN", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
    }

    @ParameterizedTest @ValueSource(strings = {"CONTENT", "REVIEW", "MAX_CONTENT", "MAX_REVIEW"})
    void bothVersionsAreCheckedAndNeitherCanOverflow(String mismatch) throws Exception {
        var actor = fixture("ADMIN"); var seed = review(fixture("USER")); int content = 2, review = 1;
        switch (mismatch) {
            case "CONTENT" -> content = 1;
            case "REVIEW" -> review = 2;
            case "MAX_CONTENT" -> { content = Integer.MAX_VALUE; jdbc.update("UPDATE community_content SET version=? WHERE id=?", content, seed.content()); }
            default -> { review = Integer.MAX_VALUE; jdbc.update("UPDATE content_review SET version=? WHERE id=?", review, seed.review()); }
        }
        var response = decide(seed.review(), token(actor), id(), content, review, "APPROVE", "版本检查");
        assertProblem(response, 409, mismatch.endsWith("CONTENT") ? "CONTENT_VERSION_CONFLICT" : "REVIEW_VERSION_CONFLICT");
        int current = mismatch.startsWith("MAX") ? Integer.MAX_VALUE : mismatch.equals("CONTENT") ? 2 : 1;
        assertEquals(current, JsonPath.<Integer>read(response.body(), "$.currentVersion")); assertEquals(0, auditCount(seed));
    }

    @ParameterizedTest @ValueSource(strings = {"APPROVED", "REJECTED", "CANCELLED", "INVALIDATED", "DELETED", "NO_ACTIVE", "NO_WORKING", "TERMINAL_REVISION"})
    void terminalOrStaleBindingsCannotReceiveANewDecision(String state) throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER"));
        switch (state) {
            case "DELETED" -> jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?", seed.content());
            case "NO_ACTIVE" -> jdbc.update("UPDATE community_content SET active_review_id=NULL WHERE id=?", seed.content());
            case "NO_WORKING" -> jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?", seed.content());
            case "TERMINAL_REVISION" -> jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?", seed.revision());
            default -> jdbc.update("UPDATE content_review SET state=? WHERE id=?", state, seed.review());
        }
        var before = businessRows(seed);
        assertProblem(decide(seed.review(), token(actor), id(), 2, 1, "REJECT", "不应重复决定"), 409, state.equals("DELETED") ? "CONTENT_NOT_EDITABLE" : "REVIEW_NOT_PENDING");
        assertEquals(before, businessRows(seed));
    }

    @ParameterizedTest @CsvSource({"MODERATOR,APPROVE", "MODERATOR,REJECT", "ADMIN,APPROVE", "ADMIN,REJECT"})
    void evenAdminsCannotReviewTheirOwnWork(String role, String decision) throws Exception {
        var actor = fixture(role); var seed = review(actor);
        var response = decide(seed.review(), token(actor), id(), 99, 99, decision, "本人稿件");
        assertProblem(response, 403, "PERMISSION_DENIED"); assertFalse(JsonPath.<Map<String,Object>>read(response.body(), "$").containsKey("currentVersion"));
        assertEquals(0, auditCount(seed));
    }

    @ParameterizedTest @ValueSource(strings = {"MODERATOR", "ADMIN"})
    void reviewPermissionDoesNotRequireAnExtraUserRole(String role) throws Exception {
        var actor = fixture(role); var seed = review(fixture("USER"));
        jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='USER'", actor.getId());
        assertEquals(200, decide(seed.review(), token(actor), id(), 2, 1, "REJECT", "后台角色可决定").statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "NO_ROLE", "WRITE_BLOCKED", "DISABLED"})
    void eligibilityIsRecheckedAfterTheIdempotencyReservation(String change) throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER"));
        doAnswer(call -> { call.callRealMethod(); changeAccount(actor, change); return null; }).when(limiter).acquire(actor.getId(), "127.0.0.1");
        assertProblem(decide(seed.review(), token(actor), id(), 2, 1, "APPROVE", "资格复核"), change.equals("AUTH_VERSION") ? 401 : 403,
                switch (change) { case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED"; case "UNVERIFIED", "NO_ROLE" -> "PERMISSION_DENIED"; default -> "ACCOUNT_RESTRICTED"; });
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM content_review WHERE id=?", String.class, seed.review())); assertEquals(0, auditCount(seed));
    }

    @ParameterizedTest @ValueSource(strings = {"REVISION", "REVIEW", "CONTENT", "AUDIT", "EVENT", "PUBLICATION_EVENT", "SNAPSHOT"})
    void failureAtAnyWriteStageRollsBackTheCompleteDecision(String stage) throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); String token = token(actor), key = id(); var before = businessRows(seed);
        var failure = new TransientDataAccessResourceException("sensitive database detail");
        switch (stage) {
            case "REVISION" -> doThrow(failure).when(writes).decideRevision(anyString(), anyString(), anyString(), any());
            case "REVIEW" -> doThrow(failure).when(writes).decideReview(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), any());
            case "CONTENT" -> doThrow(failure).when(writes).updateContent(anyString(), anyInt(), anyString(), anyString(), anyBoolean(), any());
            case "AUDIT" -> doThrow(failure).when(writes).insertAudit(any());
            case "EVENT" -> doThrow(failure).when(writes).insertEvent(anyString(), any(), anyInt(), anyString(), anyString(), any());
            case "PUBLICATION_EVENT" -> doThrow(failure).when(writes).insertEvent(anyString(), any(), anyInt(), eq("CONTENT_PUBLISHED"), anyString(), any());
            default -> doThrow(failure).when(requests).complete(anyString(), anyString(), anyString(), anyString(), any());
        }
        var response = decide(seed.review(), token, key, 2, 1, "APPROVE", "回滚检查"); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive")); assertEquals(before, businessRows(seed));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_decision_request WHERE created_by=?", Integer.class, actor.getId()));
        reset(writes, requests); assertEquals(200, decide(seed.review(), token, key, 2, 1, "APPROVE", "回滚检查").statusCode());
    }

    @Test void differentReviewersCannotApproveAndRejectTheSamePendingReview() throws Exception {
        var a = fixture("MODERATOR"); var b = fixture("ADMIN"); var seed = review(fixture("USER")); String tokenA = token(a), tokenB = token(b);
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { call.callRealMethod(); barrier.await(10, TimeUnit.SECONDS); return null; }).when(limiter).acquire(anyString(), eq("127.0.0.1"));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var x = pool.submit(() -> decide(seed.review(), tokenA, id(), 2, 1, "APPROVE", "审核员A通过"));
            var y = pool.submit(() -> decide(seed.review(), tokenB, id(), 2, 1, "REJECT", "审核员B驳回"));
            var first = x.get(20, TimeUnit.SECONDS); var second = y.get(20, TimeUnit.SECONDS);
            assertEquals(Set.of(200,409), Set.of(first.statusCode(), second.statusCode()));
            assertProblem(first.statusCode() == 409 ? first : second, 409, "CONTENT_VERSION_CONFLICT");
            assertEquals(3, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content())); assertEquals(1, auditCount(seed));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_outbox WHERE review_id=? AND event_type='CONTENT_REVIEW_DECIDED'", Integer.class, seed.review()));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"REVIEW", "DECISION", "REASON", "CONTENT_VERSION", "REVIEW_VERSION"})
    void reusedKeysIncludeEveryBusinessParameterInTheirFingerprint(String change) throws Exception {
        var actor = fixture("ADMIN"); var seed = review(fixture("USER")); String token = token(actor), key = id();
        assertEquals(200, decide(seed.review(), token, key, 2, 1, "APPROVE", "通过").statusCode());
        String target = change.equals("REVIEW") ? id() : seed.review(), decision = change.equals("DECISION") ? "REJECT" : "APPROVE", reason = change.equals("REASON") ? "改原因" : "通过";
        var response = decide(target, token, key, change.equals("CONTENT_VERSION") ? 3 : 2, change.equals("REVIEW_VERSION") ? 2 : 1, decision, reason);
        assertProblem(response, 409, "IDEMPOTENCY_KEY_REUSED"); assertEquals(1, auditCount(seed));
    }

    @Test void successfulReplayBypassesQuotaAndCannotUndoALaterTakedown() throws Exception {
        var actor = fixture("ADMIN"); var seed = review(fixture("USER")); String token = token(actor), key = id();
        var first = decide(seed.review(), token, key, 2, 1, "APPROVE", "通过"); assertEquals(200, first.statusCode());
        jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN',version=9 WHERE id=?", seed.content());
        for (int i = 0; i < 4; i++) {
            var replay = decide(seed.review(), token, key, 2, 1, "APPROVE", "通过"); assertEquals(200, replay.statusCode());
            assertEquals(JsonPath.<Map<String,Object>>read(first.body(), "$.data"), JsonPath.read(replay.body(), "$.data"));
        }
        assertEquals("1", redis.opsForValue().get(PREFIX + "{content-review-decision}:user:" + TokenDigest.sha256(actor.getId())));
        assertProblem(decide(id(), token, id(), 2, 1, "APPROVE", "未知审核"), 404, "RESOURCE_NOT_FOUND");
        assertProblem(decide(id(), token, id(), 2, 1, "APPROVE", "未知审核"), 404, "RESOURCE_NOT_FOUND");
        var limited = decide(id(), token, id(), 2, 1, "APPROVE", "未知审核"); assertProblem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(200, decide(seed.review(), token, key, 2, 1, "APPROVE", "通过").statusCode());
        assertEquals("TAKEN_DOWN", jdbc.queryForObject("SELECT publication_status FROM community_content WHERE id=?", String.class, seed.content()));
        assertEquals(9, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, seed.content()));
    }

    @Test void aLiveReservationReturnsRetryAfterInsteadOfApplyingAnotherDecision() throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); String key = id();
        reserve(actor, seed, key); var response = decide(seed.review(), token(actor), key, 2, 1, "APPROVE", "通过");
        assertProblem(response, 409, "REQUEST_IN_PROGRESS"); assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0); assertEquals(0, auditCount(seed));
    }

    @Test void expiredLeasesCanRecoverButAnOldExecutorCannotWriteOrReleaseTheNewLease() throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); String token = token(actor), key = id(); SessionPrincipal principal = authentication.authenticate(token);
        var input = json.readValue("{\"expectedContentVersion\":2,\"expectedReviewVersion\":1,\"decision\":\"APPROVE\",\"reason\":\"通过\"}", ReviewDecisionRequest.class);
        var a = transactions.claim(seed.review(), input, TokenDigest.sha256(key), fingerprint(seed.review()), principal);
        jdbc.update("UPDATE content_review_decision_request SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", a.request().getId());
        var b = transactions.claim(seed.review(), input, TokenDigest.sha256(key), fingerprint(seed.review()), principal);
        assertEquals(ErrorCode.REQUEST_IN_PROGRESS, assertThrows(BusinessException.class, () -> transactions.finish(a, principal, "req_stale")).errorCode());
        transactions.release(a);
        assertEquals(b.request().getLeaseToken(), jdbc.queryForObject("SELECT lease_token FROM content_review_decision_request WHERE id=?", String.class, b.request().getId()));
        assertEquals(3, transactions.finish(b, principal, "req_recovered").contentVersion());
    }

    @Test void redisFailuresReleaseTheLeaseAndDoNotTouchBusinessRows() throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); var before = businessRows(seed);
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-review-decision}:")), any(), any());
        var response = decide(seed.review(), token(actor), id(), 2, 1, "APPROVE", "通过"); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive")); assertEquals(before, businessRows(seed));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_decision_request WHERE created_by=?", Integer.class, actor.getId()));
    }

    @Test void aFullyExpiredReservationCanBeReplacedWithoutDuplicatingTheDecision() throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); String key = id(); reserve(actor, seed, key);
        jdbc.update("UPDATE content_review_decision_request SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE created_by=?", actor.getId());
        assertEquals(200, decide(seed.review(), token(actor), key, 2, 1, "APPROVE", "通过").statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_review_decision_request WHERE created_by=?", Integer.class, actor.getId())); assertEquals(1, auditCount(seed));
    }

    @Test void successfulReplayStillRequiresCurrentReviewPermission() throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); String token = token(actor), key = id();
        assertEquals(200, decide(seed.review(), token, key, 2, 1, "APPROVE", "通过").statusCode());
        changeAccount(actor, "NO_ROLE"); assertProblem(decide(seed.review(), token, key, 2, 1, "APPROVE", "通过"), 403, "PERMISSION_DENIED");
        assertEquals(1, auditCount(seed));
    }

    @Test void swaggerDeclaresBothVersionBoundsTheDecisionAndRequiredIdempotencyHeader() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/admin/reviews/{reviewId}/decisions'].post");
        assertEquals("decideReview", operation.get("operationId"));
        List<Map<String,Object>> parameters = (List<Map<String,Object>>) operation.get("parameters");
        assertEquals(Set.of("reviewId", "Idempotency-Key"), parameters.stream().map(p -> (String)p.get("name")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(parameters.stream().allMatch(p -> Boolean.TRUE.equals(p.get("required"))));
        Map<String,Object> schema = JsonPath.read(response.body(), "$.components.schemas.ReviewDecisionRequest");
        assertEquals(Set.of("expectedContentVersion", "expectedReviewVersion", "decision", "reason"), new HashSet<>((List<?>)schema.get("required")));
        assertEquals(false, schema.get("additionalProperties")); Map<String,Object> fields = (Map<String,Object>)schema.get("properties");
        for (String field : List.of("expectedContentVersion", "expectedReviewVersion")) {
            var version = (Map<?,?>)fields.get(field); assertEquals("integer", version.get("type")); assertEquals(Integer.MAX_VALUE, ((Number)version.get("maximum")).intValue());
        }
    }

    private String publishedHistory(Seed seed, String publication) {
        String old = id(); LocalDateTime first = LocalDateTime.of(2026,1,1,0,0), last = first.plusDays(10);
        jdbc.update("UPDATE content_revision SET revision_number=2 WHERE id=?", seed.revision());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'APPROVED','旧公开稿','旧正文',?,?)", old, seed.content(), first, last);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status=?,first_published_at=?,published_at=? WHERE id=?", old, publication, first, last, seed.content());
        jdbc.update("UPDATE content_review SET purpose=? WHERE id=?", publication.equals("TAKEN_DOWN") ? "REMEDIATION" : "UPDATE", seed.review()); return old;
    }
    private List<Object> businessRows(Seed seed) {
        return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()), jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", seed.review()),
                jdbc.queryForList("SELECT * FROM content_review_decision_audit WHERE review_id=?", seed.review()),
                jdbc.queryForList("SELECT * FROM content_review_outbox WHERE review_id=? ORDER BY id", seed.review()));
    }
    private int auditCount(Seed seed) { return jdbc.queryForObject("SELECT COUNT(*) FROM content_review_decision_audit WHERE review_id=?", Integer.class, seed.review()); }
    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
    private String fingerprint(String review) { return TokenDigest.sha256(json.writeValueAsString(List.of(review, 2, 1, "APPROVE", "通过"))); }
    private void reserve(UserAccount actor, Seed seed, String key) {
        jdbc.update("INSERT INTO content_review_decision_request(id,created_by,key_hash,fingerprint,state,review_id,lease_token,lease_until,created_at,expires_at) VALUES(?,?,?,?,'PROCESSING',?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 24 HOUR))",
                id(), actor.getId(), TokenDigest.sha256(key), fingerprint(seed.review()), seed.review(), id());
    }
    private void changeAccount(UserAccount actor, String change) {
        switch (change) {
            case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", actor.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", actor.getId());
            case "NO_ROLE" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code IN ('MODERATOR','ADMIN')", actor.getId());
            default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, actor.getId());
        }
    }

    private record Seed(String content, String revision, String review) { }
    private Seed review(UserAccount author) {
        String content = id(), revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'IN_REVIEW','冻结标题','冻结正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content);
        String review = ContentTestSupport.pending(jdbc, content, revision);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", revision, review, content);
        return new Seed(content, revision, review);
    }
    private UserAccount fixture(String role) {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("review-decision-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        actors.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", user.getId(), role);
        return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "review-decision-test").accessToken(); }
    private HttpResponse<String> decide(String review, String token, String key, int contentVersion, int reviewVersion, String decision, String reason) throws Exception {
        var body = Map.of("expectedContentVersion", contentVersion, "expectedReviewVersion", reviewVersion, "decision", decision, "reason", reason);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/reviews/" + review + "/decisions"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
