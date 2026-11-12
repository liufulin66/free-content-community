package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.dto.AdminReviewDetail;
import com.freecontent.community.content.model.persistence.RevisionRow;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.content.review.mapper.AdminReviewQueryMapper;
import com.freecontent.community.content.mapper.ContentReadMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class AdminReviewDetailFlowIT {
    private static final String PREFIX = "test:admin-review-detail:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private AdminReviewQueryMapper reviews;
    @MockitoSpyBean private ContentReadMapper contents;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<String> actors = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.query", "content.admin-review-query"))
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        registry.add("app.content.admin-review-query.user-per-minute-limit", () -> 3);
    }
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String actor : actors) ContentTestSupport.clearReviews(jdbc, actor);
        for (String actor : actors) {
            ContentTestSupport.clearWorkspace(jdbc, actor);
        }
        for (String actor : actors) {
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", actor); jdbc.update("DELETE FROM media_asset WHERE owner_id=?", actor);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", actor); users.deleteById(actor);
        }
    }

    @Test void moderatorReadsTheBoundRevisionAndTheCurrentWorkspace() throws Exception {
        var moderator = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author);
        var response = detail(seed.review(), token(moderator));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(seed.review(), JsonPath.read(response.body(), "$.data.review.id"));
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.revision.id"));
        assertEquals("冻结标题", JsonPath.read(response.body(), "$.data.revision.title"));
        assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.content.version"));
        assertEquals(1, JsonPath.<Integer>read(response.body(), "$.data.review.version"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals(Set.of("review", "content", "revision"), JsonPath.<Map<String,Object>>read(response.body(), "$.data").keySet());
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "APPROVED", "REJECTED", "CANCELLED", "INVALIDATED"})
    void allReviewStatesRemainReadableWithTheirCurrentFeedbackAndIndependentVersions(String state) throws Exception {
        var actor = fixture("ADMIN"); var seed = review(fixture("USER"));
        if (!state.equals("PENDING")) finish(seed, state);
        var response = detail(seed.review(), token(actor)); assertEquals(200, response.statusCode(), response.body());
        assertEquals(state, JsonPath.read(response.body(), "$.data.review.state"));
        assertEquals(state.equals("PENDING") ? 1 : 2, JsonPath.<Integer>read(response.body(), "$.data.review.version"));
        assertEquals(state.equals("PENDING") ? 2 : 7, JsonPath.<Integer>read(response.body(), "$.data.content.version"));
        assertEquals(state.equals("PENDING") ? null : "历史反馈😀", JsonPath.read(response.body(), "$.data.review.reason"));
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.revision.id"));
        assertEquals(state.equals("PENDING") ? "IN_REVIEW" : state.equals("INVALIDATED") ? "CANCELLED" : state, JsonPath.read(response.body(), "$.data.revision.status"));
    }

    @Test void historicalSnapshotCurrentDraftAndPublishedRevisionKeepTheirOwnReferences() throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author); finish(seed, "REJECTED");
        String frozenCover = asset(author), a = asset(author), b = asset(author), publishedCover = asset(author), draftCover = asset(author);
        String firstTag = tag(author), secondTag = tag(author), draft = revision(seed.content(), 3, "DRAFT", "新工作稿"), published = revision(seed.content(), 2, "APPROVED", "已发布稿");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'IMAGE',0),(?,?,'IMAGE',1),(?,?,'COVER',0),(?,?,'COVER',0)",
                seed.revision(), frozenCover, seed.revision(), b, seed.revision(), a, published, publishedCover, draft, draftCover);
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0),(?,?,1),(?,?,0)", seed.revision(), secondTag, seed.revision(), firstTag, draft, firstTag);
        jdbc.update("UPDATE community_content SET working_revision_id=?,published_revision_id=?,publication_status='PUBLISHED',version=9 WHERE id=?", draft, published, seed.content());

        var response = detail(seed.review(), token(actor)); assertEquals(200, response.statusCode(), response.body());
        assertEquals("冻结标题", JsonPath.read(response.body(), "$.data.revision.title"));
        assertEquals(frozenCover, JsonPath.read(response.body(), "$.data.revision.coverAssetId"));
        assertEquals(List.of(b, a), JsonPath.read(response.body(), "$.data.revision.imageAssetIds"));
        assertEquals(List.of(secondTag, firstTag), JsonPath.read(response.body(), "$.data.revision.tagIds"));
        assertEquals(draft, JsonPath.read(response.body(), "$.data.content.workingRevision.id"));
        assertEquals("新工作稿", JsonPath.read(response.body(), "$.data.content.workingRevision.title"));
        assertEquals(draftCover, JsonPath.read(response.body(), "$.data.content.workingRevision.coverAssetId"));
        assertEquals(published, JsonPath.read(response.body(), "$.data.content.publishedRevision.id"));
        assertEquals(publishedCover, JsonPath.read(response.body(), "$.data.content.publishedRevision.coverAssetId"));
        assertEquals(9, JsonPath.<Integer>read(response.body(), "$.data.content.version"));
        assertNull(JsonPath.read(response.body(), "$.data.content.activeReviewId"));
    }

    @Test void videoReferencesAreReadWithoutIssuingMediaUrlsOrRequiringReady() throws Exception {
        var actor = fixture("MODERATOR"); var author = fixture("USER"); var seed = review(author); String video = asset(author);
        jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?", seed.content());
        jdbc.update("UPDATE media_asset SET scene='CONTENT_VIDEO',declared_content_type='video/mp4',state='PROCESSING' WHERE id=?", video);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'VIDEO',0)", seed.revision(), video);
        var response = detail(seed.review(), token(actor)); assertEquals(200, response.statusCode(), response.body());
        assertEquals(video, JsonPath.read(response.body(), "$.data.revision.videoAssetId"));
        assertFalse(response.body().contains("storageKey")); assertFalse(response.body().contains("singleUploadTarget"));
    }

    @ParameterizedTest @ValueSource(strings = {"UNPUBLISHED", "PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"})
    void deletedAndUnavailableAuthorAccountsDoNotHideReviewHistory(String publication) throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author); finish(seed, "INVALIDATED");
        if (!publication.equals("UNPUBLISHED")) {
            String published = revision(seed.content(), 2, "APPROVED", "历史发布稿");
            jdbc.update("UPDATE community_content SET published_revision_id=? WHERE id=?", published, seed.content());
        }
        jdbc.update("UPDATE community_content SET lifecycle='DELETED',publication_status=?,deleted_at=UTC_TIMESTAMP(6) WHERE id=?", publication, seed.content());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", author.getId());
        var response = detail(seed.review(), token(actor)); assertEquals(200, response.statusCode(), response.body());
        assertEquals("DELETED", JsonPath.read(response.body(), "$.data.content.lifecycle"));
        assertEquals(publication, JsonPath.read(response.body(), "$.data.content.publicationStatus"));
        assertEquals(seed.revision(), JsonPath.read(response.body(), "$.data.revision.id"));
    }

    @ParameterizedTest @ValueSource(strings = {"MODERATOR", "ADMIN"})
    void ownReviewsAndWriteBlockedReviewersWithoutUserRoleCanBeRead(String role) throws Exception {
        var actor = fixture(role); var seed = review(actor);
        jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='USER'", actor.getId());
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", actor.getId());
        assertEquals(200, detail(seed.review(), token(actor)).statusCode());
    }

    @Test void normalAuthorsAndMissingIdentitiesCannotUseTheAdminEndpoint() throws Exception {
        var author = fixture("USER"); var seed = review(author);
        assertProblem(detail(seed.review(), token(author)), 403, "PERMISSION_DENIED");
        assertProblem(detail(seed.review(), null), 401, "AUTHENTICATION_REQUIRED");
        verifyNoInteractions(reviews, contents); assertTrue(redis.keys(PREFIX + "{content-admin-review-query}:*").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "NO_REVIEW_ROLE", "DISABLED"})
    void readTransactionRechecksTheCurrentReviewPermission(String change) throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); String token = token(actor);
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
        assertProblem(detail(seed.review(), token), change.equals("AUTH_VERSION") ? 401 : 403,
                switch (change) { case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED"; case "DISABLED" -> "ACCOUNT_RESTRICTED"; default -> "PERMISSION_DENIED"; });
        verifyNoInteractions(reviews, contents);
    }

    @Test void missingReviewsAndRepeatedReadsShareTheListQuota() throws Exception {
        var actor = fixture("ADMIN"); var seed = review(fixture("USER")); String token = token(actor);
        assertEquals(200, get("/api/v1/admin/reviews", token).statusCode());
        assertProblem(detail(id(), token), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, detail(seed.review(), token).statusCode());
        var limited = detail(seed.review(), token); assertProblem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertTrue(redis.keys(PREFIX + "{content-query}:*").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"REVIEW", "CONTENT", "REVISION", "REDIS"})
    void dependencyFailuresReturn503WithoutInternalDetails(String stage) throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER"));
        var failure = new TransientDataAccessResourceException("sensitive database detail");
        switch (stage) {
            case "REVIEW" -> doThrow(failure).when(reviews).findById(anyString());
            case "CONTENT" -> doThrow(failure).when(contents).findById(anyString());
            case "REVISION" -> doThrow(failure).when(contents).findRevision(anyString(), anyString());
            default -> doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                    argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-admin-review-query}:")), any(), any());
        }
        var response = detail(seed.review(), token(actor)); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive"));
        if (stage.equals("REDIS")) verifyNoInteractions(reviews, contents);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_CONTENT", "MISSING_FROZEN", "WRONG_CONTENT", "DRAFT_FROZEN", "TERMINAL_WORKING", "PUBLISHED_DRAFT", "DUPLICATE_COVER"})
    void inconsistentAssociationsFailClosedInsteadOfSubstitutingTheCurrentDraft(String damage) throws Exception {
        var actor = fixture("ADMIN"); var author = fixture("USER"); var seed = review(author);
        switch (damage) {
            case "MISSING_CONTENT" -> doReturn(null).when(contents).findById(seed.content());
            case "MISSING_FROZEN" -> doReturn(null).when(contents).findRevision(seed.revision(), seed.content());
            case "WRONG_CONTENT" -> doReturn(new RevisionRow(seed.revision(), id(), 1, "IN_REVIEW", "不可读", "", LocalDateTime.now(), LocalDateTime.now()))
                    .when(contents).findRevision(seed.revision(), seed.content());
            case "DRAFT_FROZEN" -> jdbc.update("UPDATE content_revision SET status='DRAFT' WHERE id=?", seed.revision());
            case "TERMINAL_WORKING" -> jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?", seed.revision());
            case "PUBLISHED_DRAFT" -> {
                String draft = revision(seed.content(), 2, "APPROVED", "错误发布稿");
                jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED' WHERE id=?", draft, seed.content());
                jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?", draft);
            }
            default -> jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'COVER',1)",
                    seed.revision(), asset(author), seed.revision(), asset(author));
        }
        assertProblem(detail(seed.review(), token(actor)), 503, "DEPENDENCY_UNAVAILABLE");
    }

    @Test void reviewSnapshotAndWorkspaceVersionsComeFromOneDatabaseSnapshot() throws Exception {
        var actor = fixture("MODERATOR"); var seed = review(fixture("USER")); String token = token(actor);
        var pool = Executors.newSingleThreadExecutor();
        try {
            doAnswer(call -> {
                var row = sql.selectOne("com.freecontent.community.content.review.mapper.AdminReviewQueryMapper.findById", Map.of("reviewId", seed.review()));
                pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> finish(seed, "REJECTED"))).get(10, TimeUnit.SECONDS);
                return row;
            }).when(reviews).findById(seed.review());
            var response = detail(seed.review(), token); assertEquals(200, response.statusCode(), response.body());
            assertEquals("PENDING", JsonPath.read(response.body(), "$.data.review.state"));
            assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.content.version"));
            assertEquals("IN_REVIEW", JsonPath.read(response.body(), "$.data.revision.status"));
            reset(reviews);
            var fresh = detail(seed.review(), token); assertEquals(200, fresh.statusCode(), fresh.body());
            assertEquals("REJECTED", JsonPath.read(fresh.body(), "$.data.review.state"));
            assertEquals(7, JsonPath.<Integer>read(fresh.body(), "$.data.content.version"));
            assertNull(JsonPath.read(fresh.body(), "$.data.content.workingRevision"));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void successfulQueriesNeverModifyContentReviewOrRevisionRows() throws Exception {
        var actor = fixture("ADMIN"); var seed = review(fixture("USER")); var before = businessRows(seed);
        assertEquals(200, detail(seed.review(), token(actor)).statusCode()); assertEquals(before, businessRows(seed));
    }

    @Test void swaggerDefinesAdminReviewDetailAndOnlyTheReviewPathId() throws Exception {
        var response = get("/v3/api-docs", null); assertEquals(200, response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/admin/reviews/{reviewId}'].get");
        assertEquals("getAdminReview", operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        List<Map<String,Object>> parameters = (List<Map<String,Object>>) operation.get("parameters"); assertEquals(1, parameters.size());
        assertEquals("reviewId", parameters.get(0).get("name")); assertEquals("path", parameters.get(0).get("in")); assertEquals(true, parameters.get(0).get("required"));
        Map<String,Object> detail = JsonPath.read(response.body(), "$.components.schemas.AdminReviewDetail");
        assertEquals(Set.of("review", "content", "revision"), new HashSet<>((List<?>) detail.get("required")));
        Map<String,Object> fields = (Map<String,Object>) detail.get("properties");
        assertEquals("#/components/schemas/Review", ((Map<?,?>) fields.get("review")).get("$ref"));
        assertEquals("#/components/schemas/ContentWorkspace", ((Map<?,?>) fields.get("content")).get("$ref"));
        assertEquals("#/components/schemas/Revision", ((Map<?,?>) fields.get("revision")).get("$ref"));
    }
    private List<Object> businessRows(Seed seed) {
        return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()), jdbc.queryForMap("SELECT * FROM content_review WHERE id=?", seed.review()),
                jdbc.queryForList("SELECT * FROM content_review_outbox WHERE review_id=?", seed.review()));
    }
    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
    private void finish(Seed seed, String state) {
        jdbc.update("UPDATE community_content SET active_review_id=NULL,working_revision_id=NULL,version=7 WHERE id=?", seed.content());
        jdbc.update("UPDATE content_review SET state=?,version=2,reason='历史反馈😀',decided_at=UTC_TIMESTAMP(6) WHERE id=?", state, seed.review());
        jdbc.update("UPDATE content_revision SET status=? WHERE id=?", state.equals("INVALIDATED") ? "CANCELLED" : state, seed.revision());
        if (state.equals("APPROVED")) jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED' WHERE id=?", seed.revision(), seed.content());
    }
    private String revision(String content, int number, String state, String title) {
        String revision = id(); jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,?,?,?,'正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content, number, state, title); return revision;
    }
    private String asset(UserAccount author) {
        String asset = id(); jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,'CONTENT_IMAGE','image.png','image/png',1,?,'FAILED',UTC_TIMESTAMP(6))", asset, author.getId(), "private/" + author.getId() + "/" + asset + "/source"); return asset;
    }
    private String tag(UserAccount author) {
        String tag = id(); jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag, tag.substring(0,20), author.getId()); return tag;
    }

    private record Seed(String content, String revision, String review) { }
    private Seed review(UserAccount author) {
        String content = id(), revision = id(); LocalDateTime time = LocalDateTime.of(2026,11,10,0,0);
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',2,?,?)", content, author.getId(), time, time);
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'IN_REVIEW','冻结标题','冻结正文',?,?)", revision, content, time, time);
        String review = ContentTestSupport.pending(jdbc, content, revision);
        jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=? WHERE id=?", revision, review, content);
        return new Seed(content, revision, review);
    }
    private UserAccount fixture(String role) {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("admin-review-detail-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        actors.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", user.getId(), role);
        return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "admin-review-detail-test").accessToken(); }
    private HttpResponse<String> detail(String review, String token) throws Exception { return get("/api/v1/admin/reviews/" + review, token); }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
