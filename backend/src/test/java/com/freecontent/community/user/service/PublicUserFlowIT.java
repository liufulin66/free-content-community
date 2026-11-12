package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.service.read.PublicContentCountReader;
import com.freecontent.community.media.entity.MediaAsset;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.mapper.PublicUserMapper;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class PublicUserFlowIT {
    private static final String PREFIX = "test:public-user:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private PublicUserMapper profiles;
    @MockitoSpyBean private PublicContentMapper counts;
    @Autowired private PublicContentCountReader contentCounts;
    @Autowired private MediaAssetMapper assets;
    @Autowired private SqlSession sqlSession;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        registry.add("app.user.public-query.redis-key-prefix", () -> PREFIX);
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        reset(redis, profiles, counts);
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
    void anonymousVisitorReceivesOnlyTheSevenPublicFields() throws Exception {
        var author = author();
        var response = get(author.getId(), null);
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "displayName", "bio", "avatarUrl", "createdAt",
                "publishedContentCount", "followerCount"), data.keySet());
        assertEquals(author.getId(), data.get("id"));
        assertEquals(author.getDisplayName(), data.get("displayName"));
        assertEquals(author.getBio(), data.get("bio"));
        assertEquals(author.getCreatedAt().toInstant(ZoneOffset.UTC).toString(), data.get("createdAt"));
        assertNull(data.get("avatarUrl"));
        assertEquals(0, data.get("publishedContentCount"));
        assertEquals(0, data.get("followerCount"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(), "$.requestId"),
                response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @Test
    void publishedCountIncludesOnlyTheAuthorsCurrentlyVisiblePublishedRevisions() throws Exception {
        var author = author();
        published(author);
        var withdrawn = published(author);
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", withdrawn.content());
        var blank = published(author);
        jdbc.update("UPDATE content_revision SET title=? WHERE id=?", "\u001c\u001f\t ", blank.revision());
        var nonbreaking = published(author);
        jdbc.update("UPDATE content_revision SET title=? WHERE id=?", "\u00a0", nonbreaking.revision());
        published(author());

        var response = get(author.getId(), null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(2, JsonPath.<Integer>read(response.body(), "$.data.publishedContentCount"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "VIEWER"})
    void exceedingEitherPublicProfileBudgetReturns429WithPositiveRetryAfter(String scope) throws Exception {
        var target = author();
        String token = null;
        if (scope.equals("SOURCE")) {
            redis.opsForValue().set(PREFIX + "{user-public-query}:source:" + TokenDigest.sha256("127.0.0.1"),
                    "120", Duration.ofMinutes(1));
        } else {
            var viewer = author();
            token = sessions.create(viewer.getId(), 1, "public-user-test").accessToken();
            redis.opsForValue().set(PREFIX + "{user-public-query}:user:" + TokenDigest.sha256(viewer.getId()),
                    "60", Duration.ofMinutes(1));
        }
        var response = get(target.getId(), token);
        assertEquals(429, response.statusCode(), response.body());
        assertEquals("RATE_LIMITED", JsonPath.read(response.body(), "$.code"));
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
        verify(profiles, never()).findById(anyString());
        verify(counts, never()).countVisibleByAuthor(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "WRITE_BLOCKED", "DISABLED"})
    void restrictionsOnTheTargetAccountDoNotHideItsPublicProfile(String state) throws Exception {
        var target = author();
        published(target);
        jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", state, target.getId());
        var response = get(target.getId(), null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, JsonPath.<Integer>read(response.body(), "$.data.publishedContentCount"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "WRITE_BLOCKED"})
    void validViewersCanReadAnotherUsersPublicProfile(String state) throws Exception {
        var target = author();
        var viewer = author();
        jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", state, viewer.getId());
        var token = sessions.create(viewer.getId(), 1, "public-user-test").accessToken();
        var response = get(target.getId(), token);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(target.getId(), JsonPath.read(response.body(), "$.data.id"));
        assertEquals("1", redis.opsForValue().get(PREFIX + "{user-public-query}:user:" + TokenDigest.sha256(viewer.getId())));
        assertNull(redis.opsForValue().get(PREFIX + "{user-public-query}:user:" + TokenDigest.sha256(target.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "UNVERIFIED", "STALE", "REVOKED", "UNKNOWN"})
    void unusableRealSessionsReturn401WithoutCountingAsAnonymous(String state) throws Exception {
        var viewer = author();
        var issued = sessions.create(viewer.getId(), 1, "public-user-test");
        String token = issued.accessToken();
        switch (state) {
            case "DISABLED" -> jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", viewer.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=false WHERE id=?", viewer.getId());
            case "STALE" -> jdbc.update("UPDATE app_user SET auth_version=2 WHERE id=?", viewer.getId());
            case "REVOKED" -> sessions.revoke(viewer.getId(), issued.session().sessionId(), TokenDigest.sha256(token));
            default -> token = "z".repeat(43);
        }
        problem(get(viewer.getId(), token), 401, "AUTHENTICATION_REQUIRED");
        assertTrue(redis.keys(PREFIX + "{user-public-query}:*").isEmpty());
        verifyNoInteractions(profiles);
    }

    @Test
    void nonexistentUsersAre404AndInvalidRequestsDoNotSpendQuota() throws Exception {
        problem(get("bad.id", null), 422, "VALIDATION_ERROR");
        problem(get("a".repeat(65), null), 422, "VALIDATION_ERROR");
        problem(get("valid_user?draft=true", null), 400, "MALFORMED_REQUEST");
        assertTrue(redis.keys(PREFIX + "{user-public-query}:*").isEmpty());
        problem(get("missing_user", null), 404, "RESOURCE_NOT_FOUND");
        assertEquals("1", redis.opsForValue().get(PREFIX + "{user-public-query}:source:" + TokenDigest.sha256("127.0.0.1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNPUBLISHED", "WITHDRAWN", "TAKEN_DOWN", "DELETED", "DRAFT", "POINTER", "TIME",
            "PROCESSING", "OWNER", "SCENE", "VIDEO"})
    void contentCountExcludesNonPublicOrInvalidPublishedData(String change) throws Exception {
        var target = author();
        var seed = published(target);
        switch (change) {
            case "UNPUBLISHED", "WITHDRAWN", "TAKEN_DOWN" -> jdbc.update(
                    "UPDATE community_content SET publication_status=? WHERE id=?", change, seed.content());
            case "DELETED" -> jdbc.update("UPDATE community_content SET lifecycle='DELETED' WHERE id=?", seed.content());
            case "DRAFT" -> jdbc.update("UPDATE content_revision SET status='DRAFT' WHERE id=?", seed.revision());
            case "POINTER" -> jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?", seed.content());
            case "TIME" -> jdbc.update("UPDATE community_content SET first_published_at=NULL,published_at=NULL WHERE id=?", seed.content());
            case "VIDEO" -> jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?", seed.content());
            default -> {
                String asset = asset(target);
                jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", seed.revision(), asset);
                if (change.equals("PROCESSING")) jdbc.update("UPDATE media_asset SET state='PROCESSING' WHERE id=?", asset);
                else if (change.equals("OWNER")) jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?", author().getId(), asset);
                else jdbc.update("UPDATE media_asset SET scene='AVATAR' WHERE id=?", asset);
            }
        }
        var response = get(target.getId(), null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(0, JsonPath.<Integer>read(response.body(), "$.data.publishedContentCount"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROFILE", "COUNT", "REDIS"})
    void dependencyFailuresAre503WithoutLeakingInternalDetails(String dependency) throws Exception {
        var target = author();
        var failure = new TransientDataAccessResourceException("sensitive SQL detail");
        switch (dependency) {
            case "PROFILE" -> doThrow(failure).when(profiles).findById(anyString());
            case "COUNT" -> doThrow(failure).when(counts).countVisibleByAuthor(anyString());
            default -> doThrow(new RedisConnectionFailureException("sensitive Redis detail"))
                    .when(redis).execute(any(RedisScript.class), anyList(), any());
        }
        var response = get(target.getId(), null);
        problem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive"));
    }

    @Test
    void readingDoesNotWriteBusinessData() throws Exception {
        var target = author();
        var seed = published(target);
        var before = jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", target.getId());
        var beforeContent = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content());
        var beforeRevision = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision());
        var beforeOutbox = jdbc.queryForList("SELECT * FROM content_review_outbox WHERE content_id=?", seed.content());
        assertEquals(200, get(target.getId(), null).statusCode());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", target.getId()));
        assertEquals(beforeContent, jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()));
        assertEquals(beforeRevision, jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()));
        assertEquals(beforeOutbox, jdbc.queryForList("SELECT * FROM content_review_outbox WHERE content_id=?", seed.content()));
    }

    @Test
    void profileAndCountUseOneSnapshotDuringConcurrentWithdrawal() throws Exception {
        var target = author();
        var seed = published(target);
        var changed = new AtomicBoolean();
        var pool = Executors.newSingleThreadExecutor();
        try {
            doAnswer(call -> {
                var profile = sqlSession.selectOne(PublicUserMapper.class.getName() + ".findById", Map.of("id", target.getId()));
                if (changed.compareAndSet(false, true)) pool.submit(() -> jdbc.update(
                        "UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", seed.content())).get(5, TimeUnit.SECONDS);
                return profile;
            }).when(profiles).findById(target.getId());
            var first = get(target.getId(), null);
            assertEquals(200, first.statusCode(), first.body());
            assertEquals(1, JsonPath.<Integer>read(first.body(), "$.data.publishedContentCount"));
            var next = get(target.getId(), null);
            assertEquals(200, next.statusCode(), next.body());
            assertEquals(0, JsonPath.<Integer>read(next.body(), "$.data.publishedContentCount"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSharedCountReaderRequiresTheCallersTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> contentCounts.countByAuthor("author_1"));
    }

    @Test
    void openApiDeclaresOptionalAuthenticationAndThePublicUserSchema() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/users/{userId}'].get");
        assertEquals("getPublicUser", operation.get("operationId"));
        assertEquals(List.of(Map.of(), Map.of("bearerAuth", List.of())), operation.get("security"));
        assertEquals(Set.of("200", "400", "401", "404", "422", "429", "500", "503"),
                ((Map<?, ?>) operation.get("responses")).keySet());
        Map<String, Object> schema = JsonPath.read(response.body(), "$.components.schemas.PublicUser");
        try (var input = getClass().getResourceAsStream("/contracts/public-content-schemas.json")) {
            assertNotNull(input);
            Map<String, Object> baseline = JsonPath.parse(input).read("$.PublicUser");
            assertEquals(new java.util.HashSet<>((List<?>) baseline.get("required")),
                    new java.util.HashSet<>((List<?>) schema.get("required")));
            assertEquals(((Map<?, ?>) baseline.get("properties")).keySet(),
                    ((Map<?, ?>) schema.get("properties")).keySet());
        }
    }

    private Seed published(UserAccount author) {
        String content = UUID.randomUUID().toString().replace("-", "");
        String revision = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,1,'APPROVED','发布标题','公开正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',"
                + "first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?", revision, content);
        return new Seed(content, revision);
    }

    private record Seed(String content, String revision) { }

    private UserAccount author() {
        var user = LoginTestSupport.account();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail("public-user-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        user.setBio("公开简介");
        users.insert(user);
        owners.add(user.getId());
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }

    private String asset(UserAccount owner) {
        var asset = new MediaAsset();
        asset.setId(UUID.randomUUID().toString().replace("-", ""));
        asset.setOwnerId(owner.getId());
        asset.setScene("CONTENT_IMAGE");
        asset.setState("READY");
        asset.setFileName("test.png");
        asset.setDeclaredContentType("image/png");
        asset.setDeclaredSizeBytes(1L);
        asset.setStorageKey("private/" + owner.getId() + "/" + asset.getId() + "/source");
        asset.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        assets.insert(asset);
        return asset.getId();
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertFalse(JsonPath.<Map<String, Object>>read(response.body(), "$").containsKey("data"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private HttpResponse<String> get(String userId, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/" + userId))
                .timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
