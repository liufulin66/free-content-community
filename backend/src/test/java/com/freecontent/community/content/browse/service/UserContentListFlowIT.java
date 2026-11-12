package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.user.mapper.PublicUserMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class UserContentListFlowIT {
    private static final String PREFIX = "test:user-content-list:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private PublicContentMapper reads;
    @MockitoSpyBean private PublicUserMapper profiles;
    @Autowired private PublicContentCursor cursors;
    @Autowired private RedisSessionStore sessions;
    @Autowired private SqlSession sqlSession;
    @Autowired private PublicContentCursorProperties cursorProperties;
    @Autowired private JsonMapper json;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        for (String scope : List.of("auth.login", "content.public-query", "user.public-query")) {
            registry.add("app." + scope + ".redis-key-prefix", () -> PREFIX);
        }
    }

    @AfterEach
    void cleanup() {
        reset(redis, reads, profiles);
        var keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            ContentTestSupport.clearReviews(jdbc, owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test
    void anonymousVisitorsCanReadAnExistingAuthorsEmptyPublicPage() throws Exception {
        var response = get(author().getId(), "", null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(Set.of("items", "nextCursor", "hasMore", "snapshotAt"),
                JsonPath.<Map<String, Object>>read(response.body(), "$.data").keySet());
        assertEquals(List.of(), JsonPath.read(response.body(), "$.data.items"));
        assertEquals(false, JsonPath.read(response.body(), "$.data.hasMore"));
        assertNull(JsonPath.read(response.body(), "$.data.nextCursor"));
        assertNotNull(JsonPath.read(response.body(), "$.data.snapshotAt"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void onlyTheChosenAuthorsPublishedCardsArePagedByFirstPublicationAndId() throws Exception {
        var target = author();
        var other = author();
        var firstAt = LocalDateTime.of(2026, 11, 3, 8, 0);
        var low = publish(target, "author_a_" + id(), firstAt);
        var high = publish(target, "author_b_" + id(), firstAt);
        var newest = publish(target, "author_c_" + id(), firstAt.plusSeconds(1));
        publish(other, id(), firstAt.plusDays(1));
        var hidden = publish(target, id(), firstAt.plusDays(2));
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", hidden.content());

        var first = get(target.getId(), "limit=2", null);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(List.of(newest.content(), high.content()), ids(first));
        assertEquals(true, JsonPath.read(first.body(), "$.data.hasMore"));
        String next = JsonPath.read(first.body(), "$.data.nextCursor");
        String snapshot = JsonPath.read(first.body(), "$.data.snapshotAt");
        publish(target, id(), LocalDateTime.ofInstant(Instant.parse(snapshot).plusSeconds(1), ZoneOffset.UTC));
        var second = get(target.getId(), "limit=2&cursor=" + next, null);
        assertEquals(200, second.statusCode(), second.body());
        assertEquals(List.of(low.content()), ids(second));
        assertEquals(snapshot, JsonPath.read(second.body(), "$.data.snapshotAt"));
        assertEquals(false, JsonPath.read(second.body(), "$.data.hasMore"));
        assertNull(JsonPath.read(second.body(), "$.data.nextCursor"));
        Map<String, Object> card = JsonPath.read(first.body(), "$.data.items[0]");
        assertEquals(14, card.size());
        assertFalse(card.containsKey("bodyMarkdown"));
        assertFalse(card.containsKey("imageAssetIds"));
        assertFalse(card.containsKey("videoAssetId"));
        assertEquals(target.getId(), JsonPath.read(first.body(), "$.data.items[0].author.id"));
        assertEquals(3, JsonPath.<Integer>read(first.body(), "$.data.items[0].author.publishedContentCount"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER_AUTHOR", "GLOBAL_ENDPOINT", "GLOBAL_CURSOR"})
    void cursorsCannotCrossAuthorsOrPublicListEndpoints(String change) throws Exception {
        var target = author();
        var other = author();
        var base = LocalDateTime.of(2026, 11, 3, 8, 0);
        publish(target, id(), base);
        publish(target, id(), base.plusSeconds(1));
        var first = get(target.getId(), "limit=1", null);
        assertEquals(200, first.statusCode(), first.body());
        String cursor = JsonPath.read(first.body(), "$.data.nextCursor");
        HttpResponse<String> response;
        switch (change) {
            case "OTHER_AUTHOR" -> response = get(other.getId(), "limit=1&cursor=" + cursor, null);
            case "GLOBAL_ENDPOINT" -> response = request("/api/v1/contents?limit=1&cursor=" + cursor, null);
            default -> {
                var global = cursors.open(null, 1, null, null, "anonymous");
                String token = cursors.next(global, global.snapshotAt().minusSeconds(1), "last_position");
                response = get(target.getId(), "limit=1&cursor=" + token, null);
            }
        }
        problem(response, 400, "CURSOR_INVALID");
    }

    @Test
    void aMissingAuthorIs404RatherThanAnEmptySuccessfulPage() throws Exception {
        problem(get("missing_" + id(), "", null), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "VIEWER"})
    void theSharedContentSourceAndViewerBudgetsReturn429WhenExhausted(String scope) throws Exception {
        var target = author();
        String token = null;
        String key = sourceKey();
        String limit = "120";
        if (scope.equals("VIEWER")) {
            var viewer = author();
            token = sessions.create(viewer.getId(), 1, "author-list-test").accessToken();
            key = PREFIX + "{content-public-query}:user:" + TokenDigest.sha256(viewer.getId());
            limit = "60";
        }
        redis.opsForValue().set(key, limit, Duration.ofMinutes(1));
        var response = get(target.getId(), "", token);
        problem(response, 429, "RATE_LIMITED");
        assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
    }

    @Test
    void authorListsGlobalListsAndDetailsShareOneBudgetWhileProfilesUseTheirOwn() throws Exception {
        var target = author();
        var seed = publish(target, id(), LocalDateTime.of(2026, 11, 3, 8, 0));
        assertEquals(200, get(target.getId(), "", null).statusCode());
        assertEquals(200, request("/api/v1/contents?tagId=missing_" + id(), null).statusCode());
        assertEquals(200, request("/api/v1/contents/" + seed.content(), null).statusCode());
        assertEquals("3", redis.opsForValue().get(sourceKey()));
        assertEquals(200, request("/api/v1/users/" + target.getId(), null).statusCode());
        assertEquals("1", redis.opsForValue().get(PREFIX + "{user-public-query}:source:" + TokenDigest.sha256("127.0.0.1")));
        assertEquals("3", redis.opsForValue().get(sourceKey()));
    }

    private String sourceKey() {
        return PREFIX + "{content-public-query}:source:" + TokenDigest.sha256("127.0.0.1");
    }

    @Test
    void authorKeysetHasAnIndexThatCanSeekWithoutFilesort() {
        var target = author();
        publish(target, id(), LocalDateTime.of(2026, 11, 3, 8, 0));
        // FORCE INDEX 仅用于验证索引能力；生产 Mapper 继续由优化器选路。
        var plan = jdbc.queryForList("EXPLAIN SELECT id FROM community_content FORCE INDEX (idx_content_author_public_seek) "
                + "WHERE author_id=? AND lifecycle='ACTIVE' AND publication_status='PUBLISHED' AND first_published_at<=? "
                + "ORDER BY first_published_at DESC,id DESC LIMIT 2", target.getId(), LocalDateTime.now(ZoneOffset.UTC));
        assertTrue(plan.stream().anyMatch(row -> "idx_content_author_public_seek".equals(row.get("key"))), plan.toString());
        assertTrue(plan.stream().noneMatch(row -> String.valueOf(row.get("Extra")).contains("filesort")), plan.toString());
    }

    @Test
    void fullPagesUseFiveQueriesAndEmptyPagesOnlyCheckTheAuthorAndCandidates() throws Exception {
        var target = author();
        for (int index = 0; index < 12; index++) publish(target, id(), LocalDateTime.of(2026, 11, 3, 8, 0).plusSeconds(index));
        clearInvocations(reads, profiles);
        var response = get(target.getId(), "limit=50", null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(12, ids(response).size());
        verify(profiles, times(1)).findById(target.getId());
        verify(profiles, never()).findByIds(anyList());
        verify(reads, times(1)).listVisibleByAuthor(eq(target.getId()), any(), isNull(), isNull(), eq(51));
        verify(reads, times(1)).findAssetsForRevisions(anyList());
        verify(reads, times(1)).findTagsForRevisions(anyList());
        verify(reads, times(1)).countVisibleByAuthors(List.of(target.getId()));
        verify(reads, never()).findById(anyString());
        verify(reads, never()).countVisibleByAuthor(anyString());
        var empty = author();
        clearInvocations(reads, profiles);
        assertEquals(200, get(empty.getId(), "", null).statusCode());
        verify(profiles, times(1)).findById(empty.getId());
        verify(reads, times(1)).listVisibleByAuthor(eq(empty.getId()), any(), isNull(), isNull(), eq(21));
        verify(reads, never()).findAssetsForRevisions(anyList());
        verify(reads, never()).findTagsForRevisions(anyList());
        verify(reads, never()).countVisibleByAuthors(anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "WRITE_BLOCKED", "DISABLED"})
    void targetAccountRestrictionsDoNotHideItsPublishedWorks(String state) throws Exception {
        var target = author();
        var seed = publish(target, id(), LocalDateTime.of(2026, 11, 3, 8, 0));
        jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", state, target.getId());
        var result = get(target.getId(), "", null);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(List.of(seed.content()), ids(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNPUBLISHED", "WITHDRAWN", "TAKEN_DOWN", "DELETED", "DRAFT", "POINTER", "TIME", "BLANK", "PROCESSING", "OWNER", "SCENE", "VIDEO"})
    void onlyValidPublicRevisionsAndMediaAppearInTheAuthorsList(String change) throws Exception {
        var target = author();
        var seed = publish(target, id(), LocalDateTime.of(2026, 11, 3, 8, 0));
        switch (change) {
            case "UNPUBLISHED", "WITHDRAWN", "TAKEN_DOWN" -> jdbc.update("UPDATE community_content SET publication_status=? WHERE id=?", change, seed.content());
            case "DELETED" -> jdbc.update("UPDATE community_content SET lifecycle='DELETED' WHERE id=?", seed.content());
            case "DRAFT" -> jdbc.update("UPDATE content_revision SET status='DRAFT' WHERE id=?", seed.revision());
            case "POINTER" -> jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?", seed.content());
            case "TIME" -> jdbc.update("UPDATE community_content SET first_published_at=NULL,published_at=NULL WHERE id=?", seed.content());
            case "BLANK" -> jdbc.update("UPDATE content_revision SET title=? WHERE id=?", "\u001f \t", seed.revision());
            case "VIDEO" -> jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?", seed.content());
            default -> {
                String asset = asset(target);
                jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)", seed.revision(), asset);
                if (change.equals("PROCESSING")) jdbc.update("UPDATE media_asset SET state='PROCESSING' WHERE id=?", asset);
                else if (change.equals("OWNER")) jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?", author().getId(), asset);
                else jdbc.update("UPDATE media_asset SET scene='AVATAR' WHERE id=?", asset);
            }
        }
        var result = get(target.getId(), "", null);
        assertEquals(200, result.statusCode(), result.body());
        assertTrue(ids(result).isEmpty());
    }

    private String asset(UserAccount owner) {
        String asset = id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) "
                + "VALUES(?,?,'CONTENT_IMAGE','test.png','image/png',1,?,'READY',UTC_TIMESTAMP(6))",
                asset, owner.getId(), "private/" + owner.getId() + "/" + asset + "/source");
        return asset;
    }

    @Test
    void authenticatedCursorsBindTheViewerRatherThanAnIndividualSession() throws Exception {
        var target = author();
        var viewer = author();
        var base = LocalDateTime.of(2026, 11, 3, 8, 0);
        for (int index = 0; index < 3; index++) publish(target, id(), base.plusSeconds(index));
        String token = sessions.create(viewer.getId(), 1, "author-list-test").accessToken();
        var first = get(target.getId(), "limit=1", token);
        assertEquals(200, first.statusCode(), first.body());
        String cursor = JsonPath.read(first.body(), "$.data.nextCursor");
        String replacement = sessions.create(viewer.getId(), 1, "replacement").accessToken();
        assertEquals(200, get(target.getId(), "limit=1&cursor=" + cursor, replacement).statusCode());
        String other = sessions.create(author().getId(), 1, "other-viewer").accessToken();
        problem(get(target.getId(), "limit=1&cursor=" + cursor, other), 400, "CURSOR_INVALID");
        problem(get(target.getId(), "limit=1&cursor=" + cursor, null), 400, "CURSOR_INVALID");
        var anonymous = get(target.getId(), "limit=1", null);
        assertEquals(200, anonymous.statusCode(), anonymous.body());
        problem(get(target.getId(), "limit=1&cursor=" + JsonPath.read(anonymous.body(), "$.data.nextCursor"), token), 400, "CURSOR_INVALID");
        assertEquals("3", redis.opsForValue().get(sourceKey()));
    }

    @Test
    void invalidAndExpiredCursorsDoNotSpendQuota() throws Exception {
        var target = author();
        problem(get(target.getId(), "cursor=", null), 400, "CURSOR_INVALID");
        problem(get(target.getId(), "cursor=changed", null), 400, "CURSOR_INVALID");
        problem(get(target.getId(), "cursor=" + "a".repeat(4097), null), 422, "VALIDATION_ERROR");
        problem(get(target.getId(), "limit=no", null), 400, "MALFORMED_REQUEST");
        problem(get(target.getId(), "limit=999999999999999999999", null), 422, "VALIDATION_ERROR");
        var old = new PublicContentCursor(cursorProperties, json,
                Clock.fixed(Instant.now().minusSeconds(cursorProperties.ttlSeconds() + 1L), ZoneOffset.UTC));
        var query = old.openForAuthor(null, 1, target.getId(), "anonymous");
        String expired = old.next(query, query.snapshotAt().minusSeconds(1), "old_position");
        problem(get(target.getId(), "limit=1&cursor=" + expired, null), 410, "CURSOR_EXPIRED");
        assertNull(redis.opsForValue().get(sourceKey()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHOR", "ROOT", "ASSETS", "TAGS", "COUNTS", "REDIS"})
    void failedDependenciesAre503InsteadOfSuccessfulEmptyPages(String dependency) throws Exception {
        var target = author();
        publish(target, id(), LocalDateTime.of(2026, 11, 3, 8, 0));
        var failure = new TransientDataAccessResourceException("sensitive SQL detail");
        switch (dependency) {
            case "AUTHOR" -> doThrow(failure).when(profiles).findById(anyString());
            case "ROOT" -> doThrow(failure).when(reads).listVisibleByAuthor(anyString(), any(), any(), any(), anyInt());
            case "ASSETS" -> doThrow(failure).when(reads).findAssetsForRevisions(anyList());
            case "TAGS" -> doThrow(failure).when(reads).findTagsForRevisions(anyList());
            case "COUNTS" -> doThrow(failure).when(reads).countVisibleByAuthors(anyList());
            default -> doThrow(new RedisConnectionFailureException("sensitive Redis detail"))
                    .when(redis).execute(any(RedisScript.class), anyList(), any());
        }
        var result = get(target.getId(), "", null);
        problem(result, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(result.body().contains("sensitive"));
    }

    @Test
    void targetPresenceCandidatesAndCountsShareOneDatabaseSnapshot() throws Exception {
        var target = author();
        var seed = publish(target, id(), LocalDateTime.of(2026, 11, 3, 8, 0));
        var changed = new AtomicBoolean();
        var pool = Executors.newSingleThreadExecutor();
        try {
            doAnswer(call -> {
                var profile = sqlSession.selectOne(PublicUserMapper.class.getName() + ".findById", Map.of("id", target.getId()));
                if (changed.compareAndSet(false, true)) pool.submit(() -> jdbc.update(
                        "UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?", seed.content())).get(5, TimeUnit.SECONDS);
                return profile;
            }).when(profiles).findById(target.getId());
            var first = get(target.getId(), "", null);
            assertEquals(200, first.statusCode(), first.body());
            assertEquals(List.of(seed.content()), ids(first));
            assertEquals(1, JsonPath.<Integer>read(first.body(), "$.data.items[0].author.publishedContentCount"));
            var next = get(target.getId(), "", null);
            assertEquals(200, next.statusCode(), next.body());
            assertTrue(ids(next).isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void openApiDeclaresTheAuthorPathPageParametersAndOptionalAuthentication() throws Exception {
        var response = request("/v3/api-docs", null);
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/users/{userId}/contents'].get");
        assertEquals("listUserContents", operation.get("operationId"));
        assertEquals(List.of(Map.of(), Map.of("bearerAuth", List.of())), operation.get("security"));
        assertEquals(Set.of("200", "400", "401", "404", "410", "422", "429", "500", "503"),
                ((Map<?, ?>) operation.get("responses")).keySet());
        assertEquals(Set.of("userId", "cursor", "limit"), new java.util.HashSet<>(
                JsonPath.<List<String>>read(response.body(), "$.paths['/api/v1/users/{userId}/contents'].get.parameters[*].name")));
    }

    @Test
    void defaultAndMaximumPageSizesUseFirstPublicationEvenAfterRepublishing() throws Exception {
        var target = author();
        var base = LocalDateTime.of(2026, 11, 3, 8, 0);
        var oldest = publish(target, id(), base);
        for (int index = 1; index < 51; index++) publish(target, id(), base.plusSeconds(index));
        jdbc.update("UPDATE community_content SET published_at=? WHERE id=?", base.plusDays(1), oldest.content());
        var normal = get(target.getId(), "", null);
        assertEquals(200, normal.statusCode(), normal.body());
        assertEquals(20, ids(normal).size());
        var maximum = get(target.getId(), "limit=50", null);
        assertEquals(200, maximum.statusCode(), maximum.body());
        assertEquals(50, ids(maximum).size());
        assertFalse(ids(maximum).contains(oldest.content()));
        var last = get(target.getId(), "limit=50&cursor=" + JsonPath.read(maximum.body(), "$.data.nextCursor"), null);
        assertEquals(200, last.statusCode(), last.body());
        assertEquals(List.of(oldest.content()), ids(last));
        assertNull(JsonPath.read(last.body(), "$.data.nextCursor"));
    }

    @Test
    void cardsUseThePublishedRevisionAndKeepDisabledTagsAndValidVideos() throws Exception {
        var target = author();
        var base = LocalDateTime.of(2026, 11, 3, 8, 0);
        var article = publish(target, id(), base);
        String draft = id();
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,2,'DRAFT','私有标题','私有正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", draft, article.content());
        jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?", draft, article.content());
        String tag = id();
        jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) "
                + "VALUES(?,'停用标签','说明','DISABLED',2,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", tag, target.getId());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)", article.revision(), tag);
        var video = publish(target, id(), base.plusSeconds(1));
        String asset = asset(target);
        jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?", video.content());
        jdbc.update("UPDATE content_revision SET body_markdown='' WHERE id=?", video.revision());
        jdbc.update("UPDATE media_asset SET scene='CONTENT_VIDEO',file_name='test.mp4',declared_content_type='video/mp4' WHERE id=?", asset);
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'VIDEO',0)", video.revision(), asset);
        var result = get(target.getId(), "", null);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(List.of(video.content(), article.content()), ids(result));
        assertEquals("VIDEO", JsonPath.read(result.body(), "$.data.items[0].kind"));
        assertEquals("", JsonPath.read(result.body(), "$.data.items[0].summary"));
        assertEquals(article.revision(), JsonPath.read(result.body(), "$.data.items[1].publishedRevisionId"));
        assertEquals("DISABLED", JsonPath.read(result.body(), "$.data.items[1].tags[0].status"));
        assertFalse(result.body().contains("私有"));
    }

    @Test
    void filteredEmptyBatchesStillAdvanceByTheLastScannedCandidate() throws Exception {
        var target = author();
        var base = LocalDateTime.of(2026, 11, 3, 8, 0);
        publish(target, id(), base);
        publish(target, id(), base.plusSeconds(1));
        doAnswer(call -> ((List<String>) call.getArgument(0)).stream().map(revision ->
                new PublicContentMapper.RevisionTagRow(revision, null, null, null, null, 0)).toList())
                .when(reads).findTagsForRevisions(anyList());
        var first = get(target.getId(), "limit=1", null);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(ids(first).isEmpty());
        assertEquals(true, JsonPath.read(first.body(), "$.data.hasMore"));
        String cursor = JsonPath.read(first.body(), "$.data.nextCursor");
        assertNotNull(cursor);
        var last = get(target.getId(), "limit=1&cursor=" + cursor, null);
        assertEquals(200, last.statusCode(), last.body());
        assertTrue(ids(last).isEmpty());
        assertEquals(false, JsonPath.read(last.body(), "$.data.hasMore"));
        assertNull(JsonPath.read(last.body(), "$.data.nextCursor"));
    }

    @Test
    void readingAndRepeatingAPageDoesNotWriteBusinessData() throws Exception {
        var target = author();
        var seed = publish(target, id(), LocalDateTime.of(2026, 11, 3, 8, 0));
        var before = List.of(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", target.getId()),
                jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()),
                jdbc.queryForList("SELECT * FROM content_review_outbox WHERE content_id=?", seed.content()));
        assertEquals(200, get(target.getId(), "", null).statusCode());
        assertEquals(200, get(target.getId(), "", null).statusCode());
        var after = List.of(jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", target.getId()),
                jdbc.queryForMap("SELECT * FROM community_content WHERE id=?", seed.content()),
                jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?", seed.revision()),
                jdbc.queryForList("SELECT * FROM content_review_outbox WHERE content_id=?", seed.content()));
        assertEquals(before, after);
        assertEquals("2", redis.opsForValue().get(sourceKey()));
    }

    private Seed publish(UserAccount author, String content, LocalDateTime firstAt) {
        String revision = id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,1,'APPROVED','公开标题','# 已发布正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", revision, content);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',"
                + "first_published_at=?,published_at=? WHERE id=?", revision, firstAt, firstAt, content);
        return new Seed(content, revision);
    }

    private List<String> ids(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.data.items[*].id");
    }

    private record Seed(String content, String revision) { }

    private UserAccount author() {
        var owner = LoginTestSupport.account();
        owner.setId(id());
        owner.setEmail("user-list-" + owner.getId() + "@example.test");
        owner.setEmailNormalized(owner.getEmail());
        users.insert(owner);
        owners.add(owner.getId());
        roles.insertDefaultRole(owner.getId(), owner.getCreatedAt());
        return owner;
    }

    private HttpResponse<String> get(String author, String query, String token) throws Exception {
        return request("/api/v1/users/" + author + "/contents" + (query.isEmpty() ? "" : "?" + query), token);
    }

    private HttpResponse<String> request(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JsonPath.read(response.body(), "$.code"));
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
