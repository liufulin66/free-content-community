package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.content.review.mapper.ContentReviewQueryMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ContentReviewQueryFlowIT {
    private static final String PREFIX = "test:review-query:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private ContentReviewQueryMapper reviews;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.creation", "content.query", "content.review-submission"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        registry.add("app.content.query.user-per-minute-limit", () -> 3);
    }
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) ContentTestSupport.clearReviews(jdbc, owner);
        for (String owner : owners) {
            jdbc.update("DELETE FROM content_creation_request WHERE created_by=?", owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        for (String owner : owners) { jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner); users.deleteById(owner); }
    }

    @Test void submissionLocationReturnsTheCurrentReviewAndContentVersion() throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        var response = get(submitted.location(), token);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(submitted.data(), JsonPath.read(response.body(), "$.data"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertNotNull(JsonPath.read(response.body(), "$.serverTime"));
        Map<String,Object> view = JsonPath.read(response.body(), "$.data.review");
        assertEquals(Set.of("id","contentId","revisionId","purpose","state","version","submittedAt","decidedAt","reviewerId","reason"), view.keySet());
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "APPROVED", "REJECTED", "CANCELLED", "INVALIDATED"})
    void readsAllReviewStatesAndFeedbackFromTheDatabase(String state) throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        String reason = "请补充说明😀".repeat(10); LocalDateTime decided = LocalDateTime.of(2026,11,10,3,20,30,123456000);
        String reviewer = fixture().getId(); boolean pending = state.equals("PENDING");
        if (!pending) {
            jdbc.update("UPDATE community_content SET active_review_id=NULL,working_revision_id=NULL,version=7 WHERE id=?", submitted.content());
            jdbc.update("UPDATE content_revision SET status=? WHERE id=?", switch (state) { case "APPROVED" -> "APPROVED"; case "REJECTED" -> "REJECTED"; default -> "CANCELLED"; }, submitted.revision());
            jdbc.update("UPDATE content_review SET state=?,version=3,decided_at=?,reviewer_id=?,reason=? WHERE id=?", state, decided, reviewer, reason, submitted.review());
        }
        var response = get(submitted.location(), token); assertEquals(200, response.statusCode(), response.body());
        assertEquals(state, JsonPath.read(response.body(), "$.data.review.state"));
        assertEquals(pending ? 1 : 3, JsonPath.<Integer>read(response.body(), "$.data.review.version"));
        assertEquals(pending ? 2 : 7, JsonPath.<Integer>read(response.body(), "$.data.contentVersion"));
        assertEquals(pending ? null : reason, JsonPath.read(response.body(), "$.data.review.reason"));
        assertEquals(pending ? null : reviewer, JsonPath.read(response.body(), "$.data.review.reviewerId"));
        assertEquals(pending ? null : decided.toInstant(ZoneOffset.UTC).toString(), JsonPath.read(response.body(), "$.data.review.decidedAt"));
        assertEquals(submitted.revision(), JsonPath.read(response.body(), "$.data.review.revisionId"));
    }

    @ParameterizedTest @ValueSource(strings = {"INITIAL", "UPDATE", "REMEDIATION"})
    void readsTheStoredReviewPurposeWithoutRecomputingIt(String purpose) throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        jdbc.update("UPDATE content_review SET purpose=? WHERE id=?", purpose, submitted.review());
        var response = get(submitted.location(), token); assertEquals(200, response.statusCode(), response.body());
        assertEquals(purpose, JsonPath.read(response.body(), "$.data.review.purpose"));
    }

    @ParameterizedTest @ValueSource(strings = {"UNPUBLISHED", "PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"})
    void ownRecycleBinContentRemainsReadableRegardlessOfPublicationState(String publication) throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        jdbc.update("UPDATE community_content SET lifecycle='DELETED',publication_status=?,active_review_id=NULL,working_revision_id=NULL,deleted_at=UTC_TIMESTAMP(6),version=4 WHERE id=?", publication, submitted.content());
        jdbc.update("UPDATE content_review SET state='INVALIDATED',version=2 WHERE id=?", submitted.review());
        jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?", submitted.revision());
        var response = get(submitted.location(), token); assertEquals(200, response.statusCode(), response.body());
        assertEquals("INVALIDATED", JsonPath.read(response.body(), "$.data.review.state")); assertEquals(4, JsonPath.<Integer>read(response.body(), "$.data.contentVersion"));
    }

    @Test void historicalReviewsRemainReadableWhenAnotherReviewIsActive() throws Exception {
        var owner = fixture(); String token = token(owner); var old = submit(token); String revision = id();
        jdbc.update("UPDATE community_content SET active_review_id=NULL,working_revision_id=NULL WHERE id=?", old.content());
        jdbc.update("UPDATE content_review SET state='REJECTED',reason='旧稿驳回',version=2 WHERE id=?", old.review());
        jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?", old.revision());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'IN_REVIEW','新标题','新正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, old.content());
        String current = ContentTestSupport.pending(jdbc, old.content(), revision);
        jdbc.update("UPDATE community_content SET active_review_id=?,working_revision_id=?,version=9 WHERE id=?", current, revision, old.content());
        var response = get(old.location(), token); assertEquals(200, response.statusCode(), response.body());
        assertEquals(old.review(), JsonPath.read(response.body(), "$.data.review.id"));
        assertEquals("旧稿驳回", JsonPath.read(response.body(), "$.data.review.reason")); assertEquals(9, JsonPath.<Integer>read(response.body(), "$.data.contentVersion"));
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "MODERATOR", "ADMIN"})
    void otherAuthorsCannotReadTheReviewOrAnyVersionInformation(String role) throws Exception {
        var owner = fixture(); var other = fixture(); var submitted = submit(token(owner));
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", other.getId(), role);
        var response = get(submitted.location(), token(other)); assertProblem(response, 404, "RESOURCE_NOT_FOUND");
        Map<String,Object> body = JsonPath.read(response.body(), "$"); assertFalse(body.containsKey("data")); assertFalse(body.containsKey("currentVersion"));
    }

    @ParameterizedTest @ValueSource(strings = {"CONTENT", "REVIEW", "PAIR"})
    void nonexistentOrMismatchedContentAndReviewIdsAre404(String missing) throws Exception {
        var owner = fixture(); String token = token(owner); var a = submit(token); var b = submit(token);
        String path = switch (missing) {
            case "CONTENT" -> path(id(), a.review()); case "REVIEW" -> path(a.content(), id()); default -> path(a.content(), b.review());
        };
        assertProblem(get(path, token), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void writeBlockedAccountsCanReadTheirReview() throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        changeAccount(owner, "WRITE_BLOCKED"); assertEquals(200, get(submitted.location(), token).statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "NO_USER", "DISABLED"})
    void transactionRechecksEligibilityAfterTheBearerFilter(String change) throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        doAnswer(call -> { var principal = call.callRealMethod(); changeAccount(owner, change); return principal; }).when(authentication).authenticate(token);
        assertProblem(get(submitted.location(), token), change.equals("AUTH_VERSION") ? 401 : 403,
                switch (change) { case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED"; case "DISABLED" -> "ACCOUNT_RESTRICTED"; default -> "PERMISSION_DENIED"; });
        verifyNoInteractions(reviews);
    }

    @ParameterizedTest @ValueSource(strings = {"DETAIL", "LIST", "REVIEW", "NOT_FOUND", "SOURCE"})
    void allContentReadsShareTheQuotaAnd404IsCounted(String firstRead) throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        if (firstRead.equals("SOURCE")) {
            redis.opsForValue().set(PREFIX + "{content-query}:source:" + TokenDigest.sha256("127.0.0.1"), "120", Duration.ofMinutes(1));
        } else {
            var first = get(switch (firstRead) { case "DETAIL" -> "/api/v1/creator/contents/" + submitted.content();
                case "LIST" -> "/api/v1/creator/contents"; case "NOT_FOUND" -> path(submitted.content(), id()); default -> submitted.location(); }, token);
            assertEquals(firstRead.equals("NOT_FOUND") ? 404 : 200, first.statusCode(), first.body());
            assertEquals(200, get(submitted.location(), token).statusCode()); assertEquals(200, get(submitted.location(), token).statusCode());
        }
        var response = get(submitted.location(), token); assertProblem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals("1", redis.opsForValue().get(PREFIX + "{content-review-submission}:user:" + TokenDigest.sha256(owner.getId())));
    }

    @Test void queriesNeverModifyBusinessDataOrIdempotencySnapshots() throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        var before = businessRows(submitted);
        assertEquals(200, get(submitted.location(), token).statusCode()); assertEquals(200, get(submitted.location(), token).statusCode());
        assertEquals(before, businessRows(submitted));
    }
    private List<Object> businessRows(Submitted input) {
        return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", input.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", input.revision()),
                jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", input.review()),
                jdbc.queryForList("SELECT * FROM content_review_outbox WHERE review_id=?", input.review()),
                jdbc.queryForList("SELECT * FROM content_review_submission_request WHERE review_id=?", input.review()),
                jdbc.queryForList("SELECT * FROM content_creation_request WHERE content_id=?", input.content()));
    }

    @Test void databaseFailuresReturn503WithoutInternalDetails() throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        doThrow(new TransientDataAccessResourceException("sensitive database detail")).when(reviews).findOwned(anyString(), anyString(), anyString());
        var response = get(submitted.location(), token); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive database detail"));
    }

    @Test void unconfirmedRedisDecisionsStopBeforeReadingReviewData() throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-query}:")), any(), any());
        var response = get(submitted.location(), token); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive redis detail")); verifyNoInteractions(reviews);
    }

    @Test void reviewAndContentVersionsCannotBeMixedAcrossAConcurrentDecision() throws Exception {
        var owner = fixture(); String token = token(owner); var submitted = submit(token);
        var pool = Executors.newSingleThreadExecutor();
        try {
            doAnswer(call -> {
                var row = sql.selectOne("com.freecontent.community.content.review.mapper.ContentReviewQueryMapper.findOwned",
                        Map.of("contentId", submitted.content(), "reviewId", submitted.review(), "owner", owner.getId()));
                pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.update("UPDATE community_content SET version=7,active_review_id=NULL,working_revision_id=NULL WHERE id=?", submitted.content());
                    jdbc.update("UPDATE content_review SET state='REJECTED',version=2,reason='并发反馈' WHERE id=?", submitted.review());
                    jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?", submitted.revision());
                })).get(10, TimeUnit.SECONDS);
                return row;
            }).when(reviews).findOwned(submitted.content(), submitted.review(), owner.getId());
            var response = get(submitted.location(), token); assertEquals(200, response.statusCode(), response.body());
            assertEquals(submitted.data(), JsonPath.read(response.body(), "$.data"));
            reset(reviews);
            var current = get(submitted.location(), token); assertEquals(200, current.statusCode(), current.body());
            assertEquals(7, JsonPath.<Integer>read(current.body(), "$.data.contentVersion"));
            assertEquals("REJECTED", JsonPath.read(current.body(), "$.data.review.state"));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void swaggerDeclaresBothPathIdsAndTheSharedReviewResultSchema() throws Exception {
        var response = get("/v3/api-docs", null); assertEquals(200, response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}/reviews/{reviewId}'].get");
        assertEquals("getMyReview", operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        List<Map<String,Object>> parameters = (List<Map<String,Object>>) operation.get("parameters");
        assertEquals(2, parameters.size());
        assertEquals(Set.of("contentId", "reviewId"), parameters.stream().map(p -> p.get("name")).collect(java.util.stream.Collectors.toSet()));
        for (var parameter : parameters) {
            assertEquals("path", parameter.get("in")); assertEquals(true, parameter.get("required"));
            Map<String,Object> schema = (Map<String,Object>) parameter.get("schema");
            assertEquals("string", schema.get("type")); assertEquals(1, schema.get("minLength")); assertEquals(64, schema.get("maxLength"));
            assertEquals("^[A-Za-z0-9_-]+$", schema.get("pattern"));
        }
        Map<String,Object> result = JsonPath.read(response.body(), "$.components.schemas.ReviewResult");
        assertEquals(Set.of("review", "contentVersion"), new HashSet<>((List<?>) result.get("required")));
    }

    private void changeAccount(UserAccount owner, String change) {
        switch (change) {
            case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", owner.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", owner.getId());
            case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner.getId());
            default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, owner.getId());
        }
    }
    private String path(String content, String review) { return "/api/v1/creator/contents/" + content + "/reviews/" + review; }
    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    private record Submitted(String content, String revision, String review, Map<String,Object> data, String location) { }
    private Submitted submit(String token) throws Exception {
        var created = send("POST", "/api/v1/creator/contents", token, "{\"kind\":\"ARTICLE\",\"title\":\"审核标题\",\"bodyMarkdown\":\"审核正文\"}", id());
        assertEquals(201, created.statusCode(), created.body());
        String content = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var submitted = send("POST", "/api/v1/creator/contents/" + content + "/reviews", token,
                json.writeValueAsString(Map.of("expectedVersion", 1, "revisionId", revision)), id());
        assertEquals(201, submitted.statusCode(), submitted.body());
        return new Submitted(content, revision, JsonPath.read(submitted.body(), "$.data.review.id"), JsonPath.read(submitted.body(), "$.data"), submitted.headers().firstValue("Location").orElseThrow());
    }
    private UserAccount fixture() {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("review-query-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        owners.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt()); return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "review-query-test").accessToken(); }
    private HttpResponse<String> get(String path, String token) throws Exception { return send("GET", path, token, null, null); }
    private HttpResponse<String> send(String method, String path, String token, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
