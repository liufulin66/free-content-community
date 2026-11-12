package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.dto.ReviewPage;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.content.review.mapper.AdminReviewQueryMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
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
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class AdminReviewListFlowIT {
    private static final String PREFIX = "test:admin-review-list:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private AdminReviewQueryMapper reviews;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<String> actors = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.query", "tag.query", "content.admin-review-query"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        registry.add("app.content.admin-review-query.user-per-minute-limit", () -> 3);
    }
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String actor : actors) ContentTestSupport.clearReviews(jdbc, actor);
        for (String actor : actors) {
            jdbc.update("UPDATE community_content SET working_revision_id=NULL,published_revision_id=NULL WHERE author_id=?", actor);
            jdbc.update("DELETE r FROM content_revision r JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", actor);
            jdbc.update("DELETE FROM community_content WHERE author_id=?", actor);
        }
        for (String actor : actors) { jdbc.update("DELETE FROM app_user_role WHERE user_id=?", actor); users.deleteById(actor); }
    }

    @Test void moderatorsCanReadThePendingReviewCollection() throws Exception {
        var moderator = fixture("MODERATOR"); var owner = fixture("USER");
        review(moderator, "PENDING", "INITIAL", LocalDateTime.of(2026,1,1,0,0));
        review(owner, "PENDING", "UPDATE", LocalDateTime.of(2026,1,2,0,0));
        var response = get("", token(moderator));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, JsonPath.<Integer>read(response.body(), "$.data.page"));
        assertEquals(20, JsonPath.<Integer>read(response.body(), "$.data.pageSize"));
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE state='PENDING'", Long.class),
                JsonPath.<Number>read(response.body(), "$.data.total").longValue());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @ParameterizedTest @CsvSource({"PENDING,INITIAL", "APPROVED,UPDATE", "REJECTED,REMEDIATION", "CANCELLED,INITIAL", "INVALIDATED,UPDATE"})
    void stateAndPurposeFiltersIntersectWithoutChangingTheBoundRevision(String state, String purpose) throws Exception {
        var moderator = fixture("MODERATOR"); var owner = fixture("USER"); LocalDateTime time = LocalDateTime.of(2026,1,1,0,0);
        var expected = review(owner, state, purpose, time);
        var wrongPurpose = review(owner, state, purpose.equals("INITIAL") ? "UPDATE" : "INITIAL", time);
        var wrongState = review(owner, state.equals("PENDING") ? "REJECTED" : "PENDING", purpose, time);
        jdbc.update("UPDATE content_review SET reviewer_id=? WHERE id=? AND state<>'PENDING'", moderator.getId(), expected.review());
        var response = get("?pageSize=100&state=" + state + "&purpose=" + purpose, token(moderator)); assertEquals(200, response.statusCode(), response.body());
        List<Map<String,Object>> items = items(response);
        assertTrue(items.stream().allMatch(row -> state.equals(row.get("state")) && purpose.equals(row.get("purpose"))));
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE state=? AND purpose=?", Long.class, state, purpose),
                JsonPath.<Number>read(response.body(), "$.data.total").longValue());
        var actual = items.stream().filter(row -> expected.review().equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals(expected.revision(), actual.get("revisionId")); assertEquals(expected.content(), actual.get("contentId"));
        assertEquals(Set.of("id","contentId","revisionId","purpose","state","version","submittedAt","decidedAt","reviewerId","reason"), actual.keySet());
        assertEquals(state.equals("PENDING") ? null : "历史反馈", actual.get("reason"));
        assertEquals(state.equals("PENDING") ? null : moderator.getId(), actual.get("reviewerId"));
        assertFalse(items.stream().anyMatch(row -> wrongPurpose.review().equals(row.get("id")) || wrongState.review().equals(row.get("id"))));
    }

    @ParameterizedTest @ValueSource(strings = {"INITIAL", "UPDATE", "REMEDIATION"})
    void purposeOnlyRetainsThePendingDefault(String purpose) throws Exception {
        var moderator = fixture("ADMIN"); var expected = review(moderator, "PENDING", purpose, LocalDateTime.of(2026,1,1,0,0));
        review(moderator, "REJECTED", purpose, LocalDateTime.of(2026,1,2,0,0));
        var response = get("?purpose=" + purpose + "&pageSize=100", token(moderator)); assertEquals(200, response.statusCode(), response.body());
        assertTrue(items(response).stream().allMatch(row -> row.get("state").equals("PENDING") && row.get("purpose").equals(purpose)));
        assertTrue(items(response).stream().anyMatch(row -> row.get("id").equals(expected.review())));
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "APPROVED", "REJECTED", "CANCELLED", "INVALIDATED"})
    void submissionTimeThenIdGiveAStableQueueOrHistoryOrder(String state) throws Exception {
        var moderator = fixture("MODERATOR"); LocalDateTime time = LocalDateTime.of(state.equals("PENDING") ? 2020 : 2030,1,1,0,0);
        var first = review(moderator, state, "REMEDIATION", time);
        var tied = review(moderator, state, "REMEDIATION", time);
        var later = review(moderator, state, "REMEDIATION", time.plusDays(1));
        List<String> sameTime = new ArrayList<>(List.of(first.review(), tied.review())); sameTime.sort(String::compareTo);
        var expected = new ArrayList<>(sameTime); expected.add(later.review()); if (!state.equals("PENDING")) Collections.reverse(expected);
        var response = get("?pageSize=100&state=" + state + "&purpose=REMEDIATION", token(moderator)); assertEquals(200, response.statusCode(), response.body());
        assertEquals(expected, items(response).stream().map(row -> (String) row.get("id")).filter(expected::contains).toList());
    }

    @Test void pagesHaveTheCorrectTotalAndHugeOrPastEndPagesAreEmpty() throws Exception {
        var moderator = fixture("MODERATOR"); LocalDateTime time = LocalDateTime.of(2026,1,1,0,0);
        for (int i = 0; i < 5; i++) review(moderator, "REJECTED", "INITIAL", time.plusDays(i));
        var expected = jdbc.queryForList("SELECT id FROM content_review WHERE state='REJECTED' AND purpose='INITIAL' ORDER BY submitted_at DESC,id DESC", String.class);
        String token = token(moderator);
        var response = get("?state=REJECTED&purpose=INITIAL&page=2&pageSize=2", token); assertEquals(200, response.statusCode(), response.body());
        assertEquals(expected.subList(2,4), items(response).stream().map(row -> row.get("id")).toList());
        assertEquals(expected.size(), JsonPath.<Integer>read(response.body(), "$.data.total"));
        for (String page : List.of(Integer.toString(expected.size() + 1), "9".repeat(80))) {
            var empty = get("?state=REJECTED&purpose=INITIAL&page=" + page, token); assertEquals(200, empty.statusCode(), empty.body());
            assertTrue(items(empty).isEmpty()); assertEquals(expected.size(), JsonPath.<Integer>read(empty.body(), "$.data.total"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"MODERATOR", "ADMIN"})
    void eitherReviewRoleWorksWithoutAUserRoleAndWriteBlockedCanRead(String role) throws Exception {
        var actor = fixture(role); jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='USER'", actor.getId());
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", actor.getId());
        assertEquals(200, get("", token(actor)).statusCode());
    }

    @Test void missingIdentityAndUsersWithoutReviewPermissionNeverConsumeQuota() throws Exception {
        assertProblem(get("", null), 401, "AUTHENTICATION_REQUIRED");
        var actor = fixture("USER"); assertProblem(get("", token(actor)), 403, "PERMISSION_DENIED");
        assertTrue(redis.keys(PREFIX + "{content-admin-review-query}:*").isEmpty()); verifyNoInteractions(reviews);
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "NO_REVIEW_ROLE", "DISABLED"})
    void currentEligibilityIsRecheckedInsideTheReadTransaction(String change) throws Exception {
        var actor = fixture("MODERATOR"); String token = token(actor);
        doAnswer(call -> {
            var principal = call.callRealMethod();
            switch (change) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", actor.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", actor.getId());
                case "NO_REVIEW_ROLE" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='MODERATOR'", actor.getId());
                default -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", actor.getId());
            }
            return principal;
        }).when(authentication).authenticate(token);
        assertProblem(get("", token), change.equals("AUTH_VERSION") ? 401 : 403,
                switch (change) { case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED"; case "DISABLED" -> "ACCOUNT_RESTRICTED"; default -> "PERMISSION_DENIED"; });
        verifyNoInteractions(reviews);
    }

    @Test void recycleBinAndNewWorkingRevisionsDoNotHideHistoricalReviews() throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); LocalDateTime time = LocalDateTime.of(2026,1,1,0,0);
        var old = review(author, "REJECTED", "REMEDIATION", time); String next = id();
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','新标题','新正文',?,?)", next, old.content(), time, time);
        jdbc.update("UPDATE community_content SET working_revision_id=?,lifecycle='DELETED',publication_status='TAKEN_DOWN',deleted_at=UTC_TIMESTAMP(6) WHERE id=?", next, old.content());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        var response = get("?state=REJECTED&purpose=REMEDIATION&pageSize=100", token(actor)); assertEquals(200, response.statusCode(), response.body());
        var item = items(response).stream().filter(row -> row.get("id").equals(old.review())).findFirst().orElseThrow();
        assertEquals(old.revision(), item.get("revisionId")); assertNotEquals(next, item.get("revisionId"));
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "SOURCE"})
    void repeatedAndEmptyPagesConsumeOnlyTheIndependentAdminQuota(String scope) throws Exception {
        var actor = fixture("ADMIN"); String token = token(actor), empty = "?page=" + "9".repeat(80);
        for (int i = 0; i < 3; i++) { var response = get(empty, token); assertEquals(200, response.statusCode(), response.body()); assertTrue(items(response).isEmpty()); }
        if (scope.equals("SOURCE")) redis.opsForValue().set(PREFIX + "{content-admin-review-query}:source:" + TokenDigest.sha256("127.0.0.1"), "120", Duration.ofMinutes(1));
        String limitedToken = scope.equals("SOURCE") ? token(fixture("ADMIN")) : token;
        var response = get("", limitedToken); assertProblem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertTrue(redis.keys(PREFIX + "{content-query}:*").isEmpty()); assertTrue(redis.keys(PREFIX + "{tag-query}:*").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"COUNT", "PAGE", "REDIS"})
    void dependencyFailuresReturn503WithoutInternalDetails(String failure) throws Exception {
        var actor = fixture("MODERATOR"); review(actor, "PENDING", "INITIAL", LocalDateTime.of(2026,1,1,0,0));
        if (failure.equals("COUNT")) doThrow(new TransientDataAccessResourceException("sensitive database detail")).when(reviews).count(anyString(), nullable(String.class));
        else if (failure.equals("PAGE")) doThrow(new TransientDataAccessResourceException("sensitive database detail")).when(reviews).page(anyString(), nullable(String.class), anyLong(), anyInt());
        else doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                    argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-admin-review-query}:")), any(), any());
        var response = get("", token(actor)); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive"));
        if (failure.equals("REDIS")) verifyNoInteractions(reviews);
    }

    @Test void countingAndPageMembershipUseOneSnapshotDuringConcurrentSubmission() throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); LocalDateTime time = LocalDateTime.of(2020,1,1,0,0);
        review(author, "PENDING", "INITIAL", time); long before = jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE state='PENDING'", Long.class);
        var pool = Executors.newSingleThreadExecutor(); var added = new AtomicReference<Seed>();
        try {
            doAnswer(call -> {
                Long count = sql.selectOne("com.freecontent.community.content.review.mapper.AdminReviewQueryMapper.count", Collections.singletonMap("state", "PENDING"));
                pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> added.set(review(author, "PENDING", "INITIAL", time.minusDays(1))))).get(10, TimeUnit.SECONDS);
                return count;
            }).when(reviews).count("PENDING", null);
            var response = get("?pageSize=100", token(actor)); assertEquals(200, response.statusCode(), response.body());
            assertEquals(before, JsonPath.<Number>read(response.body(), "$.data.total").longValue());
            assertFalse(items(response).stream().anyMatch(row -> row.get("id").equals(added.get().review())));
            reset(reviews);
            var fresh = get("?pageSize=100", token(actor)); assertEquals(before + 1, JsonPath.<Number>read(fresh.body(), "$.data.total").longValue());
            assertTrue(items(fresh).stream().anyMatch(row -> row.get("id").equals(added.get().review())));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void readingDoesNotChangeAnyBusinessRow() throws Exception {
        var actor = fixture("ADMIN"); var seed = review(actor, "PENDING", "INITIAL", LocalDateTime.of(2026,1,1,0,0));
        var before = businessRows(seed);
        assertEquals(200, get("", token(actor)).statusCode()); assertEquals(before, businessRows(seed));
    }

    @Test void swaggerDefinesOnlyContractFiltersAndReviewPage() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/admin/reviews'].get");
        assertEquals("listAdminReviews", operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        List<Map<String,Object>> parameters = (List<Map<String,Object>>) operation.get("parameters");
        assertEquals(Set.of("page","pageSize","state","purpose"), parameters.stream().map(p -> (String) p.get("name")).collect(java.util.stream.Collectors.toSet()));
        for (var parameter : parameters) { assertEquals("query", parameter.get("in")); assertEquals(false, parameter.get("required")); }
        Map<String,Object> pageSchema = JsonPath.read(response.body(), "$.components.schemas.ReviewPage");
        assertEquals(Set.of("items","page","pageSize","total"), new HashSet<>((List<?>) pageSchema.get("required")));
        Map<String,Object> fields = (Map<String,Object>) pageSchema.get("properties");
        assertEquals("#/components/schemas/Review", ((Map<?,?>) ((Map<?,?>) fields.get("items")).get("items")).get("$ref"));
        assertEquals(100, ((Number) ((Map<?,?>) fields.get("pageSize")).get("maximum")).intValue());
    }
    private List<Object> businessRows(Seed seed) {
        return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()), jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", seed.review()),
                jdbc.queryForList("SELECT * FROM content_review_outbox WHERE review_id=?", seed.review()));
    }
    private List<Map<String,Object>> items(HttpResponse<String> response) { return JsonPath.read(response.body(), "$.data.items"); }
    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private record Seed(String content, String revision, String review) { }
    private Seed review(UserAccount owner, String state, String purpose, LocalDateTime submittedAt) {
        String content = id(), revision = id(), review = id(); boolean pending = state.equals("PENDING");
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',2,?,?)", content, owner.getId(), submittedAt, submittedAt);
        String revisionState = switch (state) { case "PENDING" -> "IN_REVIEW"; case "APPROVED" -> "APPROVED"; case "REJECTED" -> "REJECTED"; default -> "CANCELLED"; };
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,?,'冻结标题','冻结正文',?,?)", revision, content, revisionState, submittedAt, submittedAt);
        jdbc.update("INSERT INTO content_review(id,content_id,revision_id,purpose,state,version,submitted_at,decided_at,reason) VALUES(?,?,?,?,?,?,?,?,?)",
                review, content, revision, purpose, state, pending ? 1 : 2, submittedAt, pending ? null : submittedAt.plusMinutes(1), pending ? null : "历史反馈");
        if (pending) jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", revision, review, content);
        return new Seed(content, revision, review);
    }
    private UserAccount fixture(String role) {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("admin-review-list-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        actors.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", user.getId(), role);
        return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "admin-review-list-test").accessToken(); }
    private HttpResponse<String> get(String query, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin/reviews" + query)).timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
