package com.freecontent.community.content.workspace.service;

import com.freecontent.community.content.workspace.dto.WorkspacePage;
import com.freecontent.community.content.support.ContentTestSupport;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.mapper.ContentReadMapper;
import com.freecontent.community.content.ratelimit.ContentQueryRateLimiter;
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
import java.time.LocalDateTime;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,properties = {
        "app.auth.email-challenge.delivery-enabled=false","app.media.upload-cleanup.enabled=false"})
class ContentListFlowIT {
    private static final String PREFIX = "test:content-list:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private ContentReadMapper contents;
    @Autowired private ContentQueryRateLimiter limiter;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix",() -> PREFIX);
        registry.add("app.content.creation.redis-key-prefix",() -> PREFIX);
        registry.add("app.content.query.redis-key-prefix",() -> PREFIX);
        registry.add("app.content.query.user-per-minute-limit",() -> 10);
    }
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX+"*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            ContentTestSupport.clearReviews(jdbc,owner);
            jdbc.update("DELETE FROM content_creation_request WHERE created_by=?",owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
            jdbc.update("DELETE FROM community_tag WHERE created_by=?",owner);
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner); users.deleteById(owner);
        }
    }

    @Test void defaultsReturnOnlyTheOwnersActiveWorkspacesWithTheContractPageShape() throws Exception {
        UserAccount owner = fixture(),other = fixture(); String token = token(owner);
        var own = create(token,Map.of("kind","ARTICLE","title","Own draft"));
        var deleted = create(token,Map.of("kind","VIDEO")); create(token(other),Map.of("kind","ARTICLE","title","Other author"));
        String deletedId = JsonPath.read(deleted.body(),"$.data.id");
        jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?",deletedId);

        var response = list("",token);

        assertEquals(200,response.statusCode(),response.body());
        Map<String,Object> data = JsonPath.read(response.body(),"$.data");
        assertEquals(Set.of("items","page","pageSize","total"),data.keySet());
        assertEquals(1,((Number)data.get("page")).intValue()); assertEquals(20,((Number)data.get("pageSize")).intValue());
        assertEquals(1,((Number)data.get("total")).longValue());
        List<Map<String,Object>> items = (List<Map<String,Object>>) data.get("items"); assertEquals(1,items.size());
        assertEquals(JsonPath.<Map<String,Object>>read(own.body(),"$.data"),items.get(0));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.read(response.body(),"$.requestId"),response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @Test void lifecyclePublicationAndKindFiltersAreCombinedForTheCurrentOwner() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); var expected = new HashMap<String,String>();
        for (String lifecycle : List.of("ACTIVE","DELETED")) for (String status : List.of("UNPUBLISHED","PUBLISHED","WITHDRAWN","TAKEN_DOWN")) for (String kind : List.of("ARTICLE","VIDEO")) {
            var created = create(token,Map.of("kind",kind)); String contentId = JsonPath.read(created.body(),"$.data.id");
            if (!status.equals("UNPUBLISHED")) publication(contentId,status);
            if (lifecycle.equals("DELETED")) jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?",contentId);
            expected.put(lifecycle+status+kind,contentId);
        }
        for (String[] choice : List.of(new String[]{"ACTIVE","UNPUBLISHED","ARTICLE"},new String[]{"ACTIVE","PUBLISHED","VIDEO"},
                new String[]{"DELETED","UNPUBLISHED","VIDEO"},new String[]{"DELETED","TAKEN_DOWN","ARTICLE"},
                new String[]{"ACTIVE","WITHDRAWN","ARTICLE"},new String[]{"DELETED","WITHDRAWN","VIDEO"})) {
            var response = list("?lifecycle="+choice[0]+"&publicationStatus="+choice[1]+"&kind="+choice[2],token);
            assertEquals(200,response.statusCode(),response.body()); assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.total"));
            assertEquals(expected.get(String.join("",choice)),JsonPath.read(response.body(),"$.data.items[0].id"));
        }
        var trash = list("?lifecycle=DELETED",token); assertEquals(200,trash.statusCode(),trash.body());
        assertEquals(8,JsonPath.<Integer>read(trash.body(),"$.data.total"));
        assertTrue(JsonPath.<List<Map<String,Object>>>read(trash.body(),"$.data.items").stream().allMatch(row -> row.get("lifecycle").equals("DELETED")));
    }

    @Test void pagesUseUpdatedTimeAndIdDescendingAndReturnEmptyItemsPastTheEnd() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) ids.add(JsonPath.read(create(token,Map.of("kind","ARTICLE")).body(),"$.data.id"));
        LocalDateTime newest = LocalDateTime.of(2026,12,1,0,0);
        for (int i = 0; i < ids.size(); i++) jdbc.update("UPDATE community_content SET updated_at=?,created_at=? WHERE id=?",
                i < 2 ? newest : newest.minusDays(i),newest.plusDays(i),ids.get(i));
        var expected = new ArrayList<>(ids.subList(0,2)); expected.sort(Comparator.reverseOrder()); expected.addAll(ids.subList(2,5));
        var actual = new ArrayList<String>();
        for (int page = 1; page <= 3; page++) {
            var response = list("?page="+page+"&pageSize=2",token); assertEquals(200,response.statusCode(),response.body());
            assertEquals(5,JsonPath.<Integer>read(response.body(),"$.data.total"));
            for (Map<String,Object> item : JsonPath.<List<Map<String,Object>>>read(response.body(),"$.data.items")) actual.add((String)item.get("id"));
        }
        assertEquals(expected,actual);
        var empty = list("?page=4&pageSize=2",token); assertEquals(200,empty.statusCode(),empty.body());
        assertEquals(List.of(),JsonPath.read(empty.body(),"$.data.items")); assertEquals(5,JsonPath.<Integer>read(empty.body(),"$.data.total"));
    }

    @Test void arbitraryPositivePagesAvoidOffsetOverflowAndDoNotExecuteThePageQuery() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); create(token,Map.of("kind","ARTICLE")); clearInvocations(contents);
        BigInteger huge = new BigInteger("9".repeat(80)); var response = list("?page="+huge+"&pageSize=100",token);
        assertEquals(200,response.statusCode(),response.body()); assertEquals(huge,new BigInteger(JsonPath.read(response.body(),"$.data.page").toString()));
        assertEquals(100,JsonPath.<Integer>read(response.body(),"$.data.pageSize")); assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.total"));
        assertEquals(List.of(),JsonPath.read(response.body(),"$.data.items"));
        verify(contents,never()).pageOwned(anyString(),anyString(),nullable(String.class),nullable(String.class),anyLong(),anyInt());
        verify(contents,never()).findRevisions(anyList(),anyList());
    }

    @Test void maximumPageSizeIsHonouredWithoutTruncatingToTwentyAndQueriesStayBatched() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        for (int i = 0; i < 25; i++) create(token,Map.of("kind","ARTICLE","title","Item "+i));
        clearInvocations(contents); var response = list("?pageSize=100",token);
        assertEquals(200,response.statusCode(),response.body()); assertEquals(25,JsonPath.<List<?>>read(response.body(),"$.data.items").size());
        verify(contents,times(1)).findRevisions(anyList(),anyList()); verify(contents,times(1)).findAssetsForRevisions(anyList()); verify(contents,times(1)).findTagsForRevisions(anyList());
        verify(contents,never()).findOwned(anyString(),anyString()); verify(contents,never()).findRevision(anyString(),anyString());
        verify(contents,never()).findAssets(anyString()); verify(contents,never()).findTagIds(anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION","UNVERIFIED","DISABLED","NO_USER","WRITE_BLOCKED"})
    void currentIdentityAndRoleAreRecheckedBeforeCountingContent(String change) throws Exception {
        UserAccount owner = fixture(); String token = token(owner); create(token,Map.of("kind","ARTICLE")); clearInvocations(contents);
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            switch(change) {
                case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?",owner.getId());
                case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?",owner.getId());
                case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner.getId());
                default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?",change,owner.getId());
            }
            return principal;
        }).when(authentication).authenticate(token);
        var response = list("",token);
        if (change.equals("WRITE_BLOCKED")) { assertEquals(200,response.statusCode(),response.body()); assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.total")); }
        else {
            assertProblem(response,change.equals("AUTH_VERSION") ? 401 : 403,change.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : change.equals("DISABLED") ? "ACCOUNT_RESTRICTED" : "PERMISSION_DENIED");
            verifyNoInteractions(contents);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"USER","MODERATOR","ADMIN"})
    void rolesNeverExpandTheListBeyondTheCurrentAuthor(String role) throws Exception {
        UserAccount owner = fixture(),other = fixture(); String token = token(owner);
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",owner.getId(),role);
        create(token,Map.of("kind","ARTICLE")); create(token(other),Map.of("kind","ARTICLE"));
        var response = list("",token); assertEquals(200,response.statusCode(),response.body()); assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.total"));
        assertEquals(owner.getId(),JsonPath.read(response.body(),"$.data.items[0].authorId"));
    }

    @ParameterizedTest @ValueSource(strings = {"LIST","DETAIL","EMPTY_PAGE"})
    void listDetailAndEmptyPagesShareTheExistingQueryQuota(String first) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token,Map.of("kind","ARTICLE")).body(),"$.data.id");
        for (int i = 0; i < 9; i++) limiter.acquire(owner.getId(),"seed-"+i);
        var allowed = first.equals("DETAIL") ? get("/api/v1/creator/contents/"+contentId,token) : list(first.equals("EMPTY_PAGE") ? "?page=999999999999" : "",token);
        assertEquals(200,allowed.statusCode(),allowed.body());
        var rejected = first.equals("DETAIL") ? list("",token) : get("/api/v1/creator/contents/"+contentId,token);
        assertProblem(rejected,429,"RATE_LIMITED"); assertTrue(Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        assertEquals("1",redis.opsForValue().get(PREFIX+"{content-create}:user:"+TokenDigest.sha256(owner.getId())));
    }

    private void publication(String contentId,String status) {
        String revision = jdbc.queryForObject("SELECT working_revision_id FROM community_content WHERE id=?",String.class,contentId);
        jdbc.update("UPDATE content_revision SET status='APPROVED' WHERE id=?",revision);
        jdbc.update("UPDATE community_content SET working_revision_id=NULL,published_revision_id=?,publication_status=? WHERE id=?",revision,status,contentId);
    }

    @Test void batchLoadingPreservesBothSnapshotsReferencesNullPointersAndReadOnlyData() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String a = asset(owner,"CONTENT_IMAGE"),b = asset(owner,"CONTENT_IMAGE"),video = asset(owner,"CONTENT_VIDEO");
        String first = tag(owner),second = tag(owner); var expected = new HashMap<String,String>();
        for (int i = 0; i < 2; i++) {
            var created = create(token,Map.of("kind","VIDEO","title","Published "+i,"bodyMarkdown","![a](asset:"+a+")",
                    "coverAssetId",a,"imageAssetIds",List.of(a),"videoAssetId",video,"tagIds",List.of(first)));
            String contentId = JsonPath.read(created.body(),"$.data.id"),published = JsonPath.read(created.body(),"$.data.workingRevision.id"),working = id();
            publication(contentId,"PUBLISHED");
            jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,?,? ,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    working,contentId,i == 0 ? "DRAFT" : "IN_REVIEW","Working "+i,"![b](asset:"+b+") ![a](asset:"+a+")");
            jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'IMAGE',0),(?,?,'IMAGE',1),(?,?,'VIDEO',0)",working,b,working,b,working,a,working,video);
            jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0),(?,?,1)",working,second,working,first);
            jdbc.update("UPDATE community_content SET working_revision_id=?,active_review_id=?,version=5 WHERE id=?",working,i == 0 ? null : ContentTestSupport.pending(jdbc,contentId,working),contentId);
            expected.put(contentId,"Working "+i); expected.put(published,"Published "+i);
        }
        var ended = create(token,Map.of("kind","ARTICLE","title","Historical title"));
        String endedId = JsonPath.read(ended.body(),"$.data.id"),endedRevision = JsonPath.read(ended.body(),"$.data.workingRevision.id");
        jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?",endedRevision);
        jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?",endedId);
        var before = jdbc.queryForList("SELECT * FROM community_content WHERE author_id=? ORDER BY id",owner.getId());
        var revisionsBefore = jdbc.queryForList("SELECT r.* FROM content_revision r JOIN community_content c ON c.id=r.content_id WHERE c.author_id=? ORDER BY r.id",owner.getId());
        var requestsBefore = jdbc.queryForList("SELECT * FROM content_creation_request WHERE created_by=? ORDER BY id",owner.getId());
        clearInvocations(contents);

        var response = list("",token); assertEquals(200,response.statusCode(),response.body());

        var rows = JsonPath.<List<Map<String,Object>>>read(response.body(),"$.data.items"); assertEquals(3,rows.size());
        for (var row : rows) {
            assertFalse(row.containsKey("title"));
            if (row.get("id").equals(endedId)) { assertNull(row.get("workingRevision")); assertNull(row.get("publishedRevision")); continue; }
            var working = (Map<String,Object>)row.get("workingRevision"); var published = (Map<String,Object>)row.get("publishedRevision");
            assertEquals(expected.get(row.get("id")),working.get("title")); assertEquals(expected.get(published.get("id")),published.get("title"));
            assertEquals(List.of(b,a),working.get("imageAssetIds")); assertEquals(List.of(a),published.get("imageAssetIds"));
            assertEquals(List.of(second,first),working.get("tagIds")); assertEquals(List.of(first),published.get("tagIds"));
            assertEquals(b,working.get("coverAssetId")); assertEquals(a,published.get("coverAssetId")); assertEquals(video,working.get("videoAssetId"));
        }
        verify(contents,times(1)).findRevisions(anyList(),anyList()); verify(contents,times(1)).findAssetsForRevisions(anyList()); verify(contents,times(1)).findTagsForRevisions(anyList());
        verify(contents,never()).findRevision(anyString(),anyString());
        assertEquals(before,jdbc.queryForList("SELECT * FROM community_content WHERE author_id=? ORDER BY id",owner.getId()));
        assertEquals(revisionsBefore,jdbc.queryForList("SELECT r.* FROM content_revision r JOIN community_content c ON c.id=r.content_id WHERE c.author_id=? ORDER BY r.id",owner.getId()));
        assertEquals(requestsBefore,jdbc.queryForList("SELECT * FROM content_creation_request WHERE created_by=? ORDER BY id",owner.getId()));
    }

    private String asset(UserAccount owner,String scene) {
        String id = id(),type = scene.equals("CONTENT_VIDEO") ? "video/mp4" : "image/png";
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,actual_content_type,actual_size_bytes,created_at) VALUES(?,?,?,?,?,1,?,'READY',?,1,UTC_TIMESTAMP(6))",
                id,owner.getId(),scene,"fixture",type,"private/"+owner.getId()+"/"+id+"/source",type); return id;
    }
    private String tag(UserAccount owner) {
        String id = id(); jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                id,"List_"+id.substring(0,20),owner.getId()); return id;
    }

    @Test void countPageMembershipAndRevisionsShareOneSnapshotDuringAConcurrentCommit() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var first = create(token,Map.of("kind","ARTICLE","title","Before edit"));
        String a = JsonPath.read(first.body(),"$.data.id"),revisionA = JsonPath.read(first.body(),"$.data.workingRevision.id");
        String b = JsonPath.read(create(token,Map.of("kind","ARTICLE","title","Second")).body(),"$.data.id"),c = id(),revisionC = id();
        var writer = Executors.newSingleThreadExecutor(); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            var parameters = new HashMap<String,Object>(); parameters.put("owner",owner.getId()); parameters.put("lifecycle","ACTIVE");
            parameters.put("publicationStatus",null); parameters.put("kind",null);
            Long total = sqlSession.selectOne(ContentReadMapper.class.getName()+".countOwned",parameters);
            if (calls.getAndIncrement() == 0) writer.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE content_revision SET title='After edit' WHERE id=?",revisionA);
                jdbc.update("UPDATE community_content SET version=2,updated_at=UTC_TIMESTAMP(6) WHERE id=?",a);
                jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",c,owner.getId());
                jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'DRAFT','Concurrent creation','',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revisionC,c);
                jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?",revisionC,c);
            })).get(10,TimeUnit.SECONDS);
            return total;
        }).when(contents).countOwned(owner.getId(),"ACTIVE",null,null);
        try {
            var old = list("",token); assertEquals(200,old.statusCode(),old.body());
            assertEquals(2,JsonPath.<Integer>read(old.body(),"$.data.total"));
            var oldRows = JsonPath.<List<Map<String,Object>>>read(old.body(),"$.data.items");
            assertEquals(Set.of(a,b),oldRows.stream().map(row -> row.get("id")).collect(java.util.stream.Collectors.toSet()));
            var oldA = oldRows.stream().filter(row -> row.get("id").equals(a)).findFirst().orElseThrow();
            assertEquals(1,oldA.get("version")); assertEquals("Before edit",((Map<?,?>)oldA.get("workingRevision")).get("title"));
            var next = list("",token); assertEquals(200,next.statusCode(),next.body()); assertEquals(3,JsonPath.<Integer>read(next.body(),"$.data.total"));
            var newRows = JsonPath.<List<Map<String,Object>>>read(next.body(),"$.data.items");
            assertEquals(Set.of(a,b,c),newRows.stream().map(row -> row.get("id")).collect(java.util.stream.Collectors.toSet()));
            var newA = newRows.stream().filter(row -> row.get("id").equals(a)).findFirst().orElseThrow();
            assertEquals(2,newA.get("version")); assertEquals("After edit",((Map<?,?>)newA.get("workingRevision")).get("title"));
        } finally { writer.shutdownNow(); assertTrue(writer.awaitTermination(15,TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"COUNT","PAGE","REVISIONS","ASSETS","TAGS"})
    void databaseFailuresReturn503WithoutPartialPagesOrInternalDetails(String stage) throws Exception {
        UserAccount owner = fixture(); String token = token(owner); create(token,Map.of("kind","ARTICLE"));
        var failure = new TransientDataAccessResourceException("sensitive database detail");
        switch(stage) {
            case "COUNT" -> doThrow(failure).when(contents).countOwned(anyString(),anyString(),nullable(String.class),nullable(String.class));
            case "PAGE" -> doThrow(failure).when(contents).pageOwned(anyString(),anyString(),nullable(String.class),nullable(String.class),anyLong(),anyInt());
            case "REVISIONS" -> doThrow(failure).when(contents).findRevisions(anyList(),anyList());
            case "ASSETS" -> doThrow(failure).when(contents).findAssetsForRevisions(anyList());
            default -> doThrow(failure).when(contents).findTagsForRevisions(anyList());
        }
        var response = list("",token); assertProblem(response,503,"DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive database detail")); assertFalse(JsonPath.<Map<String,Object>>read(response.body(),"$").containsKey("data"));
    }

    @Test void redisFailureStopsBeforeAnyContentQuery() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); create(token,Map.of("kind","ARTICLE")); clearInvocations(contents);
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX+"{content-query}:")),any(),any());
        var response = list("",token); assertProblem(response,503,"DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive redis detail")); verifyNoInteractions(contents);
    }

    @Test void sourceQuotaExhaustionStopsBeforeAnyContentQuery() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); clearInvocations(contents);
        redis.opsForValue().set(PREFIX+"{content-query}:source:"+TokenDigest.sha256("127.0.0.1"),"120",Duration.ofMinutes(1));
        assertProblem(list("",token),429,"RATE_LIMITED"); verifyNoInteractions(contents);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING","TERMINAL","PUBLISHED_WITHOUT_POINTER","DOUBLE_COVER"})
    void inconsistentReferencesFailClosedInsteadOfFabricatingWorkspaces(String scenario) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token,Map.of("kind","ARTICLE"));
        String contentId = JsonPath.read(created.body(),"$.data.id"),revision = JsonPath.read(created.body(),"$.data.workingRevision.id");
        switch(scenario) {
            case "MISSING" -> doReturn(List.of()).when(contents).findRevisions(anyList(),anyList());
            case "TERMINAL" -> jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?",revision);
            case "PUBLISHED_WITHOUT_POINTER" -> jdbc.update("UPDATE community_content SET publication_status='PUBLISHED' WHERE id=?",contentId);
            default -> jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0),(?,?,'COVER',1)",revision,asset(owner,"CONTENT_IMAGE"),revision,asset(owner,"CONTENT_IMAGE"));
        }
        assertProblem(list("",token),503,"DEPENDENCY_UNAVAILABLE");
    }

    @Test void emptyPagesAndUnpointedHistoryDoNotGenerateEmptyInQueriesOrFakeTitles() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var empty = list("",token); assertEquals(200,empty.statusCode(),empty.body()); assertEquals(0,JsonPath.<Integer>read(empty.body(),"$.data.total"));
        var created = create(token,Map.of("kind","ARTICLE","title","Ended history"));
        String contentId = JsonPath.read(created.body(),"$.data.id"),revision = JsonPath.read(created.body(),"$.data.workingRevision.id");
        jdbc.update("UPDATE content_revision SET status='CANCELLED' WHERE id=?",revision);
        jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?",contentId); clearInvocations(contents);
        var page = list("",token); assertEquals(200,page.statusCode(),page.body());
        Map<String,Object> item = JsonPath.read(page.body(),"$.data.items[0]"); assertNull(item.get("workingRevision")); assertNull(item.get("publishedRevision")); assertFalse(item.containsKey("title"));
        verify(contents,never()).findRevisions(anyList(),anyList()); verify(contents,never()).findAssetsForRevisions(anyList()); verify(contents,never()).findTagsForRevisions(anyList());
    }

    @Test void unknownIdentityAndUnverifiedMailKeepContractErrors() throws Exception {
        assertProblem(list("",null),401,"AUTHENTICATION_REQUIRED"); assertProblem(list("","bad-token"),401,"AUTHENTICATION_REQUIRED");
        UserAccount owner = fixture(); String token = token(owner); jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?",owner.getId());
        assertProblem(list("",token),403,"PERMISSION_DENIED");
    }

    @Test void swaggerDeclaresOnlyTheFiveContractFiltersAndTheFullWorkspacePage() throws Exception {
        var response = get("/v3/api-docs",null); assertEquals(200,response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(),"$.paths['/api/v1/creator/contents'].get");
        assertEquals("listMyContents",operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        var parameters = (List<Map<String,Object>>)operation.get("parameters"); assertEquals(5,parameters.size());
        var schemas = new HashMap<String,Map<String,Object>>();
        for (var parameter : parameters) {
            assertEquals("query",parameter.get("in")); assertEquals(false,parameter.get("required"));
            schemas.put((String)parameter.get("name"),(Map<String,Object>)parameter.get("schema"));
        }
        assertEquals(Set.of("page","pageSize","lifecycle","publicationStatus","kind"),schemas.keySet());
        assertEquals("integer",schemas.get("page").get("type")); assertEquals(1,schemas.get("page").get("default")); assertFalse(schemas.get("page").containsKey("maximum"));
        assertEquals(20,schemas.get("pageSize").get("default")); assertEquals(100,schemas.get("pageSize").get("maximum"));
        assertEquals("ACTIVE",schemas.get("lifecycle").get("default")); assertEquals(Set.of("ACTIVE","DELETED"),new HashSet<>((List<String>)schemas.get("lifecycle").get("enum")));
        assertEquals(Set.of("ARTICLE","VIDEO"),new HashSet<>((List<String>)schemas.get("kind").get("enum")));
        Map<String,Object> page = JsonPath.read(response.body(),"$.components.schemas.WorkspacePage");
        assertEquals(Set.of("items","page","pageSize","total"),new HashSet<>((List<String>)page.get("required")));
        assertEquals("#/components/schemas/ContentWorkspace",JsonPath.read(response.body(),"$.components.schemas.WorkspacePage.properties.items.items['$ref']"));
        assertFalse(((Map<?,?>)operation.get("responses")).containsKey("404"));
    }
    private void assertProblem(HttpResponse<String> response,int status,String code) {
        assertEquals(status,response.statusCode(),response.body()); assertEquals(code,JsonPath.read(response.body(),"$.code"));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    private UserAccount fixture() {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("content-list-"+user.getId()+"@example.test"); user.setEmailNormalized(user.getEmail());
        owners.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(),user.getCreatedAt()); return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(),user.getAuthVersion(),"content-list-test").accessToken(); }
    private HttpResponse<String> create(String token,Map<String,Object> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/creator/contents")).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+token).header("Idempotency-Key",id()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = client.send(request,HttpResponse.BodyHandlers.ofString()); assertEquals(201,response.statusCode(),response.body()); return response;
    }
    private HttpResponse<String> list(String query,String token) throws Exception { return get("/api/v1/creator/contents"+query,token); }
    private HttpResponse<String> get(String path,String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization","Bearer "+token);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-",""); }
}
