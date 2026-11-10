package com.freecontent.community.content.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.mapper.ContentWorkspaceMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ContentWorkspaceFlowIT {
    private static final String PREFIX = "test:content-workspace:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @MockitoSpyBean private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private ContentWorkspaceMapper contents;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.query.user-per-minute-limit", () -> 3);
    }

    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            jdbc.update("DELETE FROM content_creation_request WHERE created_by=?", owner);
            jdbc.update("UPDATE community_content SET working_revision_id=NULL,published_revision_id=NULL WHERE author_id=?", owner);
            jdbc.update("DELETE a FROM content_revision_asset a JOIN content_revision r ON r.id=a.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
            jdbc.update("DELETE t FROM content_revision_tag t JOIN content_revision r ON r.id=t.revision_id JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
            jdbc.update("DELETE r FROM content_revision r JOIN community_content c ON c.id=r.content_id WHERE c.author_id=?", owner);
            jdbc.update("DELETE FROM community_content WHERE author_id=?", owner);
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test void theOwnerCanReadTheWorkspaceFromTheCreationLocation() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "Current draft", "bodyMarkdown", "# 当前正文"));
        assertEquals(201, created.statusCode(), created.body());

        var response = getPath(created.headers().firstValue("Location").orElseThrow(), token);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(JsonPath.<Map<String,Object>>read(created.body(), "$.data"), JsonPath.read(response.body(), "$.data"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION", "UNVERIFIED", "DISABLED", "NO_USER", "WRITE_BLOCKED"})
    void rechecksTheCurrentAccountAndRoleBeforeReadingAnyContent(String change) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token, Map.of("kind", "ARTICLE")).body(), "$.data.id");
        clearInvocations(contents);
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            switch (change) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", owner.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", owner.getId());
                case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner.getId());
                default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", change, owner.getId());
            }
            return principal;
        }).when(authentication).authenticate(token);

        var response = get(contentId, token);

        if (change.equals("WRITE_BLOCKED")) assertEquals(200, response.statusCode(), response.body());
        else {
            assertProblem(response, change.equals("AUTH_VERSION") ? 401 : 403,
                    change.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : change.equals("DISABLED") ? "ACCOUNT_RESTRICTED" : "PERMISSION_DENIED");
            verifyNoInteractions(contents);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "MODERATOR", "ADMIN"})
    void noRoleCanUseThisEndpointToReadAnotherAuthorsWorkspace(String role) throws Exception {
        UserAccount owner = fixture(), viewer = fixture();
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))", viewer.getId(), role);
        String contentId = JsonPath.read(create(token(owner), Map.of("kind", "ARTICLE")).body(), "$.data.id");
        assertProblem(get(contentId, token(viewer)), 404, "RESOURCE_NOT_FOUND");
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    @Test void readsCurrentValuesWithoutChangingContentRevisionsOrTheCreationSnapshot() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "Original"));
        String contentId = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        jdbc.update("UPDATE content_revision SET title='Edited',body_markdown='Current text',updated_at=UTC_TIMESTAMP(6) WHERE id=?", revision);
        jdbc.update("UPDATE community_content SET version=2,updated_at=UTC_TIMESTAMP(6) WHERE id=?", contentId);
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", contentId);
        var draftBefore = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", revision);
        var requestBefore = jdbc.queryForMap("SELECT * FROM content_creation_request WHERE created_by=?", owner.getId());

        var response = get(contentId, token);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals("Edited", JsonPath.read(response.body(), "$.data.workingRevision.title"));
        assertEquals("Current text", JsonPath.read(response.body(), "$.data.workingRevision.bodyMarkdown"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", contentId));
        assertEquals(draftBefore, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", revision));
        assertEquals(requestBefore, jdbc.queryForMap("SELECT * FROM content_creation_request WHERE created_by=?", owner.getId()));
    }

    @ParameterizedTest @ValueSource(strings = {"PUBLISHED", "WITHDRAWN", "TAKEN_DOWN", "DELETED"})
    void returnsBothPointedRevisionsAndTheirOwnOrderedReferencesEvenForDeletedContent(String state) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String a = asset(owner, "CONTENT_IMAGE"), b = asset(owner, "CONTENT_IMAGE"), video = asset(owner, "CONTENT_VIDEO");
        String firstTag = tag(owner), secondTag = tag(owner);
        var created = create(token, Map.of("kind", "VIDEO", "title", "Published snapshot", "bodyMarkdown", "![a](asset:" + a + ")",
                "coverAssetId", a, "imageAssetIds", List.of(a), "videoAssetId", video, "tagIds", List.of(firstTag)));
        assertEquals(201, created.statusCode(), created.body());
        String contentId = JsonPath.read(created.body(), "$.data.id"), published = JsonPath.read(created.body(), "$.data.workingRevision.id"), working = id();
        jdbc.update("UPDATE content_revision SET status='APPROVED' WHERE id=?", published);
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'IN_REVIEW','Working snapshot',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                working, contentId, "![b](asset:" + b + ") ![a](asset:" + a + ")");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'IMAGE',0),(?,?,'IMAGE',1),(?,?,'VIDEO',0)",
                working, b, working, b, working, a, working, video);
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0),(?,?,1)", working, secondTag, working, firstTag);
        boolean deleted = state.equals("DELETED");
        if (deleted) jdbc.update("UPDATE content_revision SET status='DRAFT' WHERE id=?", working);
        String review = deleted ? null : id();
        jdbc.update("UPDATE community_content SET working_revision_id=?,published_revision_id=?,active_review_id=?,publication_status=?,version=7,lifecycle=?,deleted_at=? WHERE id=?",
                working, published, review, deleted ? "WITHDRAWN" : state, deleted ? "DELETED" : "ACTIVE", deleted ? java.time.LocalDateTime.of(2026,11,1,0,0) : null, contentId);

        var response = get(contentId, token);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(7, JsonPath.<Integer>read(response.body(), "$.data.version"));
        assertEquals(deleted ? "DELETED" : "ACTIVE", JsonPath.read(response.body(), "$.data.lifecycle"));
        assertEquals(review, JsonPath.read(response.body(), "$.data.activeReviewId"));
        assertEquals("Working snapshot", JsonPath.read(response.body(), "$.data.workingRevision.title"));
        assertEquals(deleted ? "DRAFT" : "IN_REVIEW", JsonPath.read(response.body(), "$.data.workingRevision.status"));
        assertEquals("Published snapshot", JsonPath.read(response.body(), "$.data.publishedRevision.title"));
        assertEquals("APPROVED", JsonPath.read(response.body(), "$.data.publishedRevision.status"));
        assertEquals(List.of(b,a), JsonPath.read(response.body(), "$.data.workingRevision.imageAssetIds"));
        assertEquals(List.of(a), JsonPath.read(response.body(), "$.data.publishedRevision.imageAssetIds"));
        assertEquals(List.of(secondTag,firstTag), JsonPath.read(response.body(), "$.data.workingRevision.tagIds"));
        assertEquals(List.of(firstTag), JsonPath.read(response.body(), "$.data.publishedRevision.tagIds"));
        assertEquals(b, JsonPath.read(response.body(), "$.data.workingRevision.coverAssetId"));
        assertEquals(video, JsonPath.read(response.body(), "$.data.publishedRevision.videoAssetId"));
        assertEquals(deleted ? "2026-11-01T00:00:00Z" : null, JsonPath.read(response.body(), "$.data.deletedAt"));
    }

    @ParameterizedTest @ValueSource(strings = {"REJECTED", "CANCELLED"})
    void absentPointersRemainNullInsteadOfFallingBackToTheLatestRevision(String status) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE"));
        String contentId = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        jdbc.update("UPDATE content_revision SET status=? WHERE id=?", status, revision);
        jdbc.update("UPDATE community_content SET working_revision_id=NULL,version=3 WHERE id=?", contentId);
        var response = get(contentId, token); assertEquals(200, response.statusCode(), response.body());
        assertNull(JsonPath.read(response.body(), "$.data.workingRevision")); assertNull(JsonPath.read(response.body(), "$.data.publishedRevision"));
        verify(contents, never()).findRevision(anyString(), anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"WORKING_TERMINAL", "PUBLISHED_DRAFT", "PUBLISHED_WITHOUT_POINTER", "DUPLICATE_COVER"})
    void inconsistentStoredPointersOrScalarReferencesFailClosed(String scenario) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE"));
        String contentId = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        switch (scenario) {
            case "WORKING_TERMINAL" -> jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?", revision);
            case "PUBLISHED_DRAFT" -> jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED' WHERE id=?", revision, contentId);
            case "PUBLISHED_WITHOUT_POINTER" -> jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?", contentId);
            default -> jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'COVER',1)",
                    revision, asset(owner,"CONTENT_IMAGE"), revision, asset(owner,"CONTENT_IMAGE"));
        }
        assertProblem(get(contentId, token), 503, "DEPENDENCY_UNAVAILABLE");
    }

    private String asset(UserAccount owner, String scene) {
        String assetId = id(); String type = scene.equals("CONTENT_VIDEO") ? "video/mp4" : "image/png";
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,actual_content_type,actual_size_bytes,created_at) VALUES(?,?,?,?,?,1,?,'READY',?,1,UTC_TIMESTAMP(6))",
                assetId, owner.getId(), scene, "fixture", type, "private/" + owner.getId() + "/" + assetId + "/source", type);
        return assetId;
    }

    @ParameterizedTest @ValueSource(strings = {"?status=PUBLISHED", "?includeDeleted=true", "?x=1&x=2"})
    void unknownQueriesAreRejectedBeforeReadingContentOrCountingQuota(String query) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token, Map.of("kind", "ARTICLE")).body(), "$.data.id");
        clearInvocations(contents);
        var response = get(contentId + query, token);
        assertProblem(response, 400, "MALFORMED_REQUEST");
        assertEquals("query", JsonPath.read(response.body(), "$.violations[0].location"));
        verifyNoInteractions(contents); assertTrue(redis.keys(PREFIX + "{content-query}:*").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"bad.id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void malformedIdsHavePathViolationsWithoutReadingContentOrCountingQuota(String badId) throws Exception {
        UserAccount owner = fixture(); clearInvocations(contents);
        var response = get(badId, token(owner));
        assertProblem(response, 422, "VALIDATION_ERROR");
        assertEquals("path", JsonPath.read(response.body(), "$.violations[0].location"));
        assertEquals("contentId", JsonPath.read(response.body(), "$.violations[0].path"));
        verifyNoInteractions(contents); assertTrue(redis.keys(PREFIX + "{content-query}:*").isEmpty());
    }

    @Test void missingIdentityInvalidTokensAndUnverifiedMailKeepContractErrors() throws Exception {
        assertProblem(get("missing", null), 401, "AUTHENTICATION_REQUIRED");
        assertProblem(get("missing", "bad-token"), 401, "AUTHENTICATION_REQUIRED");
        UserAccount owner = fixture(); String token = token(owner);
        jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?", owner.getId());
        assertProblem(get("missing", token), 403, "PERMISSION_DENIED");
    }

    @Test void allRowsComeFromOneSnapshotWhileAnotherTransactionCommitsAnEdit() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind", "ARTICLE", "title", "Before edit"));
        String contentId = JsonPath.read(created.body(), "$.data.id"), revision = JsonPath.read(created.body(), "$.data.workingRevision.id");
        var writer = Executors.newSingleThreadExecutor(); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            ContentWorkspaceMapper.ContentRow row = sqlSession.selectOne(ContentWorkspaceMapper.class.getName() + ".findOwned", Map.of("id", contentId, "owner", owner.getId()));
            if (calls.getAndIncrement() == 0) {
                writer.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.update("UPDATE content_revision SET title='After edit' WHERE id=?", revision);
                    jdbc.update("UPDATE community_content SET version=2 WHERE id=?", contentId);
                })).get(10, TimeUnit.SECONDS);
            }
            return row;
        }).when(contents).findOwned(contentId, owner.getId());
        try {
            var oldSnapshot = get(contentId, token); assertEquals(200, oldSnapshot.statusCode(), oldSnapshot.body());
            assertEquals(1, JsonPath.<Integer>read(oldSnapshot.body(), "$.data.version"));
            assertEquals("Before edit", JsonPath.read(oldSnapshot.body(), "$.data.workingRevision.title"));
            var nextSnapshot = get(contentId, token); assertEquals(200, nextSnapshot.statusCode(), nextSnapshot.body());
            assertEquals(2, JsonPath.<Integer>read(nextSnapshot.body(), "$.data.version"));
            assertEquals("After edit", JsonPath.read(nextSnapshot.body(), "$.data.workingRevision.title"));
        } finally { writer.shutdownNow(); assertTrue(writer.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"CONTENT", "REVISION", "ASSETS", "TAGS", "ACCOUNT"})
    void databaseFailuresAre503AndDoNotLeakInternalDetails(String stage) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token, Map.of("kind", "ARTICLE")).body(), "$.data.id");
        var failure = new TransientDataAccessResourceException("sensitive database detail");
        switch (stage) {
            case "CONTENT" -> doThrow(failure).when(contents).findOwned(anyString(), anyString());
            case "REVISION" -> doThrow(failure).when(contents).findRevision(anyString(), anyString());
            case "ASSETS" -> doThrow(failure).when(contents).findAssets(anyString());
            case "TAGS" -> doThrow(failure).when(contents).findTagIds(anyString());
            default -> doThrow(failure).when(users).findByIdForShare(anyString());
        }
        var response = get(contentId, token); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive database detail"));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM community_content WHERE id=?", Integer.class, contentId));
    }

    @Test void anUnconfirmedRedisDecisionStopsBeforeContentAccess() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token, Map.of("kind", "ARTICLE")).body(), "$.data.id");
        clearInvocations(contents);
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX + "{content-query}:")), any(), any());
        var response = get(contentId, token); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive redis detail")); verifyNoInteractions(contents);
    }

    @Test void missingPointedRevisionIsNotSilentlyReportedAsNoDraft() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token, Map.of("kind", "ARTICLE")).body(), "$.data.id");
        doReturn(null).when(contents).findRevision(anyString(), anyString());
        assertProblem(get(contentId, token), 503, "DEPENDENCY_UNAVAILABLE");
    }

    @Test void trustedProxyRequestsUseTomcatsResolvedClientAddressForQuota() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token, Map.of("kind", "ARTICLE")).body(), "$.data.id");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents/" + contentId))
                .header("Authorization", "Bearer " + token).header("X-Forwarded-For", "203.0.113.7").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode(), response.body());
        assertEquals("1", redis.opsForValue().get(PREFIX + "{content-query}:source:" + TokenDigest.sha256("203.0.113.7")));
        assertNull(redis.opsForValue().get(PREFIX + "{content-query}:source:" + TokenDigest.sha256("127.0.0.1")));
    }

    @Test void swaggerDeclaresOnlyThePathParameterAndReusesTheWorkspaceResponse() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/creator/contents/{contentId}'].get");
        assertEquals("getMyContentWorkspace", operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        List<Map<String,Object>> parameters = (List<Map<String,Object>>) operation.get("parameters");
        assertEquals(1, parameters.size()); assertEquals("contentId", parameters.get(0).get("name"));
        assertEquals("path", parameters.get(0).get("in")); assertEquals(true, parameters.get(0).get("required"));
        Map<String,Object> schema = (Map<String,Object>) parameters.get(0).get("schema");
        assertEquals("string", schema.get("type")); assertEquals(1, schema.get("minLength")); assertEquals(64, schema.get("maxLength"));
        assertTrue(((Map<?,?>) operation.get("responses")).containsKey("200"));
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "SOURCE"})
    void repeatsAndNotFoundResultsConsumeIndependentQueryQuotas(String scope) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token, Map.of("kind", "ARTICLE")).body(), "$.data.id");
        assertEquals(200, get(contentId, token).statusCode());
        if (scope.equals("USER")) {
            assertProblem(get("missing_" + id(), token), 404, "RESOURCE_NOT_FOUND");
            assertEquals(200, get(contentId, token).statusCode());
        } else {
            redis.opsForValue().set(PREFIX + "{content-query}:source:" + TokenDigest.sha256("127.0.0.1"), "120", Duration.ofMinutes(1));
        }
        var limited = get(contentId, token);
        assertProblem(limited, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        assertEquals("1", redis.opsForValue().get(PREFIX + "{content-create}:user:" + TokenDigest.sha256(owner.getId())));
    }
    private String tag(UserAccount owner) {
        String tagId = id();
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                tagId, "Workspace_" + tagId.substring(0,20), owner.getId()); return tagId;
    }

    private UserAccount fixture() {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("workspace-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        owners.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt()); return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(), user.getAuthVersion(), "workspace-test").accessToken(); }
    private HttpResponse<String> create(String token, Map<String,Object> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creator/contents"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).header("Idempotency-Key", id())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String contentId, String token) throws Exception { return getPath("/api/v1/creator/contents/" + contentId, token); }
    private HttpResponse<String> getPath(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
