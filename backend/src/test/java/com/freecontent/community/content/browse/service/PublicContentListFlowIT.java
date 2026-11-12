package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.apache.ibatis.session.SqlSession;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.auth.email-challenge.delivery-enabled=false","app.media.upload-cleanup.enabled=false"})
class PublicContentListFlowIT {
    private static final String PREFIX="test:public-list:"+UUID.randomUUID()+":";
    private static final LocalDateTime BASE=LocalDateTime.of(2026,11,3,8,0);
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private PublicContentMapper reads;
    @MockitoSpyBean private PublicUserMapper profiles;
    @Autowired private SqlSession sqlSession;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JsonMapper json;
    @Autowired private PublicContentCursorProperties cursorProperties;
    private final List<String> owners=new ArrayList<>();
    private String scopeTag;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry){for(String scope:List.of("auth.login","content.public-query"))registry.add("app."+scope+".redis-key-prefix",()->PREFIX);}
    @BeforeEach void isolatedTagScope(){var owner=author();scopeTag=id();jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'测试范围','ACTIVE',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",scopeTag,"scope_"+scopeTag.substring(0,20),owner.getId());}
    @AfterEach void cleanup(){reset(reads,profiles);var keys=redis.keys(PREFIX+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        for(String owner:owners)ContentTestSupport.clearWorkspace(jdbc,owner);
        for(String owner:owners){jdbc.update("DELETE FROM community_tag WHERE created_by=?",owner);jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}}
    @Test void stableDescendingKeysetPaginationKeepsTheInitialSnapshotAndDoesNotReturnDetailFields()throws Exception{
        var owner=author();publish(owner,"c_a",BASE,"ARTICLE");publish(owner,"c_b",BASE,"ARTICLE");publish(owner,"c_C",BASE,"ARTICLE");
        var first=get("limit=2",null);ok(first);assertEquals(List.of("c_b","c_a"),ids(first));assertTrue(JsonPath.<Boolean>read(first.body(),"$.data.hasMore"));
        String next=JsonPath.read(first.body(),"$.data.nextCursor"),snapshot=JsonPath.read(first.body(),"$.data.snapshotAt");
        publish(owner,"c_new",LocalDateTime.ofInstant(Instant.parse(snapshot).plusSeconds(1),ZoneOffset.UTC),"ARTICLE");
        var second=get("limit=2&cursor="+next,null);ok(second);assertEquals(List.of("c_C"),ids(second));assertEquals(snapshot,JsonPath.read(second.body(),"$.data.snapshotAt"));
        assertFalse(JsonPath.<Boolean>read(second.body(),"$.data.hasMore"));assertNull(JsonPath.read(second.body(),"$.data.nextCursor"));
        Map<String,Object> card=JsonPath.read(first.body(),"$.data.items[0]");assertEquals(14,card.size());assertFalse(card.containsKey("bodyMarkdown"));assertFalse(card.containsKey("imageAssetIds"));assertFalse(card.containsKey("videoAssetId"));
    }
    @Test void filtersOnlyThePublishedRevisionAndKeepsDisabledTagReferences()throws Exception{
        var owner=author();var tagged=publish(owner,id(),BASE,"ARTICLE");var plain=publish(owner,id(),BASE.plusSeconds(1),"ARTICLE");
        String tag=id();jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,'停用标签','说明','DISABLED',2,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tag,owner.getId());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)",tagged.revision(),tag);
        String draft=id();jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','私有','私有',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",draft,plain.content());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)",draft,tag);jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?",draft,plain.content());
        var result=get("kind=ARTICLE&tagId="+tag,null);ok(result);assertEquals(List.of(tagged.content()),ids(result));assertEquals("DISABLED",JsonPath.read(result.body(),"$.data.items[0].tags[0].status"));
        assertEquals(2,JsonPath.<Integer>read(result.body(),"$.data.items[0].author.publishedContentCount"));
        var empty=get("tagId="+id(),null);ok(empty);assertTrue(ids(empty).isEmpty());assertNull(JsonPath.read(empty.body(),"$.data.nextCursor"));
    }
    @Test void hiddenAndDamagedRowsCannotPrematurelyEndTheScan()throws Exception{
        var owner=author();List<String> expected=new ArrayList<>();
        for(int i=0;i<3;i++)expected.add(publish(owner,"good_"+i,BASE.plusSeconds(i),"ARTICLE").content());
        for(int i=0;i<14;i++){var row=publish(owner,"bad_"+i,BASE.plusSeconds(100+i),"ARTICLE");switch(i%7){
            case 0->jdbc.update("UPDATE community_content SET lifecycle='DELETED' WHERE id=?",row.content());
            case 1->jdbc.update("UPDATE community_content SET publication_status='TAKEN_DOWN' WHERE id=?",row.content());
            case 2->jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?",row.content());
            case 3->jdbc.update("UPDATE community_content SET first_published_at=NULL,published_at=NULL WHERE id=?",row.content());
            case 4->jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?",row.revision());
            case 5->jdbc.update("UPDATE content_revision SET title='   ' WHERE id=?",row.revision());
            default->jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?",row.content());}}
        var first=get("limit=2",null);ok(first);assertEquals(List.of("good_2","good_1"),ids(first));assertTrue(JsonPath.<Boolean>read(first.body(),"$.data.hasMore"));
        var second=get("limit=2&cursor="+JsonPath.read(first.body(),"$.data.nextCursor"),null);ok(second);assertEquals(List.of("good_0"),ids(second));
        assertEquals(3,JsonPath.<Integer>read(first.body(),"$.data.items[0].author.publishedContentCount"));
    }
    @ParameterizedTest @CsvSource({"limit=0,422,VALIDATION_ERROR","limit=51,422,VALIDATION_ERROR","limit=999999999999999999999,422,VALIDATION_ERROR","limit=no,400,MALFORMED_REQUEST","kind=article,422,VALIDATION_ERROR","tagId=bad.id,422,VALIDATION_ERROR","cursor=,400,CURSOR_INVALID","cursor=changed,400,CURSOR_INVALID","unknown=1,400,MALFORMED_REQUEST","limit=1&limit=2,400,MALFORMED_REQUEST"})
    void invalidParametersAndCursorsAreRejectedBeforeQuota(String query,int status,String code)throws Exception{
        problem(get(query,null),status,code);assertNull(redis.opsForValue().get(sourceKey()));
    }
    @Test void cursorCannotChangeTheLimitFilterOrUserScopeAndExpiredCursorsAre410()throws Exception{
        var owner=author();publish(owner,id(),BASE,"ARTICLE");publish(owner,id(),BASE.plusSeconds(1),"ARTICLE");
        var first=get("limit=1",null);ok(first);String cursor=JsonPath.read(first.body(),"$.data.nextCursor");
        problem(get("limit=2&cursor="+cursor,null),400,"CURSOR_INVALID");problem(get("limit=1&kind=ARTICLE&cursor="+cursor,null),400,"CURSOR_INVALID");
        String token=sessions.create(owner.getId(),1,"list-test").accessToken();problem(get("limit=1&cursor="+cursor,token),400,"CURSOR_INVALID");
        var oldClock=Clock.fixed(Instant.now().minusSeconds(cursorProperties.ttlSeconds()+1L),ZoneOffset.UTC);var old=new PublicContentCursor(cursorProperties,json,oldClock);
        var state=old.open(null,1,null,scopeTag,"anonymous");String expired=old.next(state,state.snapshotAt().minusSeconds(1),"old_position");
        problem(get("limit=1&cursor="+expired,null),410,"CURSOR_EXPIRED");assertEquals("1",redis.opsForValue().get(sourceKey()));
    }
    @Test void anEmptyListConsumesTheSameSourceBudgetAsDetailReads()throws Exception{
        var owner=author();var row=publish(owner,id(),BASE,"ARTICLE");ok(get("tagId="+id(),null));
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/contents/"+row.content())).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());
        assertEquals("2",redis.opsForValue().get(sourceKey()));
    }
    @Test void fullPagesUseFiveBatchQueriesWithoutPerItemLookups()throws Exception{
        var first=author();var second=author();for(int i=0;i<12;i++)publish(i%2==0?first:second,id(),BASE.plusSeconds(i),"ARTICLE");
        clearInvocations(reads,profiles);var response=get("limit=50",null);ok(response);assertEquals(12,ids(response).size());
        verify(reads,times(1)).listVisible(any(),any(),any(),any(),any(),eq(51));
        verify(reads,times(1)).findAssetsForRevisions(anyList());verify(reads,times(1)).findTagsForRevisions(anyList());
        verify(reads,times(1)).countVisibleByAuthors(anyList());verify(profiles,times(1)).findByIds(anyList());
        verify(reads,never()).findById(anyString());verify(reads,never()).findAssets(anyString());verify(reads,never()).findTags(anyString());
        verify(reads,never()).countVisibleByAuthor(anyString());verify(profiles,never()).findById(anyString());
    }
    @Test void defaultsAndMaximumPageSizesAreBoundedAndTheLastPageHasNoCursor()throws Exception{
        var owner=author();for(int i=0;i<51;i++)publish(owner,id(),BASE.plusSeconds(i),"ARTICLE");
        var normal=get("",null);ok(normal);assertEquals(20,ids(normal).size());
        var maximum=get("limit=50",null);ok(maximum);assertEquals(50,ids(maximum).size());
        var last=get("limit=50&cursor="+JsonPath.read(maximum.body(),"$.data.nextCursor"),null);ok(last);assertEquals(1,ids(last).size());assertNull(JsonPath.read(last.body(),"$.data.nextCursor"));
    }
    @Test void withdrawalOfRemainingRowsDoesNotLoseLaterVisibleRows()throws Exception{
        var owner=author();List<Seed> rows=new ArrayList<>();for(int i=0;i<5;i++)rows.add(publish(owner,"moving_"+i,BASE.plusSeconds(i),"ARTICLE"));
        var first=get("limit=2",null);ok(first);assertEquals(List.of("moving_4","moving_3"),ids(first));
        jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?",rows.get(2).content());
        var next=get("limit=2&cursor="+JsonPath.read(first.body(),"$.data.nextCursor"),null);ok(next);assertEquals(List.of("moving_1","moving_0"),ids(next));assertFalse(JsonPath.<Boolean>read(next.body(),"$.data.hasMore"));
    }
    @Test void emptyFilteredBatchesStillAdvanceTheScannedPosition()throws Exception{
        var owner=author();publish(owner,"filtered_a",BASE,"ARTICLE");publish(owner,"filtered_b",BASE.plusSeconds(1),"ARTICLE");
        doReturn(List.of()).when(profiles).findByIds(anyList());
        var first=get("limit=1",null);ok(first);assertTrue(ids(first).isEmpty());assertTrue(JsonPath.<Boolean>read(first.body(),"$.data.hasMore"));
        var next=get("limit=1&cursor="+JsonPath.read(first.body(),"$.data.nextCursor"),null);ok(next);assertTrue(ids(next).isEmpty());assertFalse(JsonPath.<Boolean>read(next.body(),"$.data.hasMore"));
    }
    @Test void authenticatedCursorsBindUsersButNotIndividualSessions()throws Exception{
        var owner=author();publish(owner,id(),BASE,"ARTICLE");publish(owner,id(),BASE.plusSeconds(1),"ARTICLE");
        String token=sessions.create(owner.getId(),1,"list-test").accessToken();var first=get("limit=1",token);ok(first);String cursor=JsonPath.read(first.body(),"$.data.nextCursor");
        var other=author();String otherToken=sessions.create(other.getId(),1,"list-test").accessToken();problem(get("limit=1&cursor="+cursor,otherToken),400,"CURSOR_INVALID");
        String replacement=sessions.create(owner.getId(),1,"list-test").accessToken();ok(get("limit=1&cursor="+cursor,replacement));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"ROOT","ASSETS","TAGS","AUTHORS","COUNTS"})
    void failedBatchDependenciesAre503RatherThanAnEmptySuccessfulPage(String dependency)throws Exception{
        publish(author(),id(),BASE,"ARTICLE");var failure=new TransientDataAccessResourceException("sensitive SQL detail");
        switch(dependency){
            case "ROOT"->doThrow(failure).when(reads).listVisible(any(),any(),any(),any(),any(),anyInt());
            case "ASSETS"->doThrow(failure).when(reads).findAssetsForRevisions(anyList());
            case "TAGS"->doThrow(failure).when(reads).findTagsForRevisions(anyList());
            case "AUTHORS"->doThrow(failure).when(profiles).findByIds(anyList());
            default->doThrow(failure).when(reads).countVisibleByAuthors(anyList());
        }
        var response=get("",null);problem(response,503,"DEPENDENCY_UNAVAILABLE");assertFalse(response.body().contains("sensitive"));
    }
    @Test void aPageAndItsAuthorCountsShareOneDatabaseSnapshot()throws Exception{
        var owner=author();var seed=publish(owner,id(),BASE,"ARTICLE");String asset=id();
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,'CONTENT_IMAGE','test.png','image/png',1,?,'READY',UTC_TIMESTAMP(6))",asset,owner.getId(),"private/"+owner.getId()+"/"+asset+"/source");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)",seed.revision(),asset);
        var changed=new AtomicBoolean();var pool=Executors.newSingleThreadExecutor();
        try{
            doAnswer(call->{var result=sqlSession.selectList(PublicContentMapper.class.getName()+".findAssetsForRevisions",Map.of("revisions",call.getArgument(0)));
                if(changed.compareAndSet(false,true))pool.submit(()->jdbc.update("UPDATE media_asset SET state='FAILED' WHERE id=?",asset)).get(5,TimeUnit.SECONDS);return result;}).when(reads).findAssetsForRevisions(anyList());
            var first=get("",null);ok(first);assertEquals(List.of(seed.content()),ids(first));assertEquals(1,JsonPath.<Integer>read(first.body(),"$.data.items[0].author.publishedContentCount"));
            var second=get("",null);ok(second);assertTrue(ids(second).isEmpty());
        }finally{pool.shutdownNow();}
    }
    @Test void javaAndSqlWhitespaceRulesAgreeForVisibleAuthorCounts()throws Exception{
        var owner=author();var visible=publish(owner,id(),BASE,"ARTICLE");var control=publish(owner,id(),BASE.plusSeconds(1),"ARTICLE");var nbsp=publish(owner,id(),BASE.plusSeconds(2),"ARTICLE");
        jdbc.update("UPDATE content_revision SET title=? WHERE id=?",String.valueOf((char)0x001f),control.revision());
        jdbc.update("UPDATE content_revision SET title=? WHERE id=?",String.valueOf((char)0x00a0),nbsp.revision());
        var response=get("",null);ok(response);assertEquals(List.of(nbsp.content(),visible.content()),ids(response));
        assertEquals(2,JsonPath.<Integer>read(response.body(),"$.data.items[0].author.publishedContentCount"));
    }
    @Test void listSchemaMatchesTheOriginalContractIncludingCursorConditions()throws Exception{
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
        Map<String,Object> schemas=JsonPath.read(response.body(),"$.components.schemas");
        try(var input=getClass().getResourceAsStream("/contracts/public-content-schemas.json")){
            Map<String,Object> expected=JsonPath.parse(input).read("$");
            for(String name:List.of("ContentPage","ContentSummary")){Map<?,?> actual=(Map<?,?>)schemas.get(name),baseline=(Map<?,?>)expected.get(name);
                assertEquals(new HashSet<>((List<?>)baseline.get("required")),new HashSet<>((List<?>)actual.get("required")));
                assertEquals(((Map<?,?>)baseline.get("properties")).keySet(),((Map<?,?>)actual.get("properties")).keySet());}
        }
        assertEquals(List.of(Map.of(),Map.of("bearerAuth",List.of())),JsonPath.read(response.body(),"$.paths['/api/v1/contents'].get.security"));
        assertEquals(1,JsonPath.<Integer>read(response.body(),"$.components.schemas.ContentPage.allOf[0].then.properties.nextCursor.minLength"));
        assertEquals("string",JsonPath.read(response.body(),"$.components.schemas.ContentPage.allOf[0].then.properties.nextCursor.type"));
        assertEquals("null",JsonPath.read(response.body(),"$.components.schemas.ContentPage.allOf[0].else.properties.nextCursor.type"));
    }
    private Seed publish(UserAccount owner,String content,LocalDateTime first,String kind){String revision=id();jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,? ,?,'ACTIVE','UNPUBLISHED',3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",content,owner.getId(),kind);
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'APPROVED',?,'# 已发布正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revision,content,"公开标题"+content);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',first_published_at=?,published_at=? WHERE id=?",revision,first,first,content);jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,4)",revision,scopeTag);return new Seed(content,revision);}
    private UserAccount author(){var owner=LoginTestSupport.account();owner.setId(id());owner.setEmail("public-list-"+owner.getId()+"@example.test");owner.setEmailNormalized(owner.getEmail());owners.add(owner.getId());users.insert(owner);roles.insertDefaultRole(owner.getId(),owner.getCreatedAt());return owner;}
    private HttpResponse<String> get(String query,String token)throws Exception{if(!query.contains("tagId="))query+=(query.isEmpty()?"":"&")+"tagId="+scopeTag;var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/contents"+(query.isEmpty()?"":"?"+query))).timeout(Duration.ofSeconds(15)).GET();if(token!=null)builder.header("Authorization","Bearer "+token);return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
    private List<String> ids(HttpResponse<String> response){return JsonPath.read(response.body(),"$.data.items[*].id");}
    private String sourceKey(){return PREFIX+"{content-public-query}:source:"+TokenDigest.sha256("127.0.0.1");}
    private void ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());}
    private void problem(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));}
    private String id(){return UUID.randomUUID().toString().replace("-","");}
    private record Seed(String content,String revision){}
}
