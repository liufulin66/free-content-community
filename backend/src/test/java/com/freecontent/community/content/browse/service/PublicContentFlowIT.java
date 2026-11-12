package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.content.support.ContentTestSupport;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.freecontent.community.media.entity.MediaAsset;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.jayway.jsonpath.JsonPath;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.user.mapper.PublicUserMapper;
import org.apache.ibatis.session.SqlSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class PublicContentFlowIT {
    private static final String PREFIX="test:public-content:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private MediaAssetMapper media;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoSpyBean private PublicContentMapper reads;
    @MockitoSpyBean private PublicUserMapper profiles;
    @Autowired private SqlSession sqlSession;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    private final List<String> owners=new ArrayList<>();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        for(String scope:List.of("auth.login","content.public-query","content.creation","content.query","content.review-submission","content.review-decision"))
            registry.add("app."+scope+".redis-key-prefix",()->PREFIX);
    }
    @AfterEach void cleanup() {
        reset(reads, profiles, redis);
        var keys=redis.keys(PREFIX+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        for(String owner:owners){
            for(String table:List.of("content_review_decision_request","content_review_decision_audit","content_review_cancellation_audit","content_review_media_preview_audit","content_media_preview_audit"))
                jdbc.update("DELETE FROM "+table+" WHERE content_id IN (SELECT id FROM community_content WHERE author_id=?)",owner);
            jdbc.update("DELETE FROM content_creation_request WHERE created_by=?",owner);
            ContentTestSupport.clearReviews(jdbc,owner);ContentTestSupport.clearWorkspace(jdbc,owner);
        }
        for(String owner:owners){jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);jdbc.update("DELETE FROM community_tag WHERE created_by=?",owner);jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner);users.deleteById(owner);}
    }
    @Test void returnsOnlyThePublishedSnapshotAndDoesNotWriteBusinessData() throws Exception {
        var author=author();var seed=published(author);String draft=id();
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','私有草稿','不应泄露',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",draft,seed.content());
        jdbc.update("UPDATE community_content SET working_revision_id=? WHERE id=?",draft,seed.content());
        var before=rows(seed);var response=get(seed.content(),null);assertEquals(200,response.statusCode(),response.body());
        assertEquals("发布标题",JsonPath.read(response.body(),"$.data.title"));assertEquals(seed.revision(),JsonPath.read(response.body(),"$.data.publishedRevisionId"));
        assertEquals("正文",JsonPath.read(response.body(),"$.data.summary"));assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.author.publishedContentCount"));
        assertFalse(response.body().contains("不应泄露"));assertFalse(response.body().contains("password"));assertFalse(response.body().contains("email"));
        assertEquals(before,rows(seed));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
    }
    @ParameterizedTest @ValueSource(strings={"UNPUBLISHED","WITHDRAWN","TAKEN_DOWN","DELETED"})
    void nonPublicStatesRemain404EvenForTheAuthor(String state) throws Exception {
        var author=author();var seed=published(author);
        if(state.equals("DELETED"))jdbc.update("UPDATE community_content SET lifecycle='DELETED' WHERE id=?",seed.content());
        else jdbc.update("UPDATE community_content SET publication_status=? WHERE id=?",state,seed.content());
        problem(get(seed.content(),sessions.create(author.getId(),1,"public-test").accessToken()),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","IN_REVIEW","REJECTED","CANCELLED"})
    void onlyAnApprovedRevisionCanBePublic(String state) throws Exception {
        var seed=published(author());jdbc.update("UPDATE content_revision SET status=? WHERE id=?",state,seed.revision());
        problem(get(seed.content(),null),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"POINTER","TIME"})
    void inconsistentPublishedMetadataIs503(String change) throws Exception {
        var seed=published(author());
        if(change.equals("POINTER"))jdbc.update("UPDATE community_content SET published_revision_id=NULL WHERE id=?",seed.content());
        else jdbc.update("UPDATE community_content SET first_published_at=NULL,published_at=NULL WHERE id=?",seed.content());
        problem(get(seed.content(),null),503,"DEPENDENCY_UNAVAILABLE");
    }
    @ParameterizedTest @ValueSource(strings={"PROCESSING","FAILED","OWNER","SCENE"})
    void mediaMustBeReadyOwnedAndUsedForTheCorrectPurpose(String change) throws Exception {
        var author=author();var seed=published(author);String asset=asset(author,"CONTENT_IMAGE","READY");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)",seed.revision(),asset);
        if(change.equals("OWNER"))jdbc.update("UPDATE media_asset SET owner_id=? WHERE id=?",author().getId(),asset);
        else if(change.equals("SCENE"))jdbc.update("UPDATE media_asset SET scene='AVATAR' WHERE id=?",asset);
        else jdbc.update("UPDATE media_asset SET state=? WHERE id=?",change,asset);
        problem(get(seed.content(),null),404,"RESOURCE_NOT_FOUND");
    }
    @Test void preservesReferenceOrderAndDisabledTagsAndCountsOnlyVisibleContents() throws Exception {
        var author=author();var seed=published(author);String first=asset(author,"CONTENT_IMAGE","READY"),second=asset(author,"CONTENT_IMAGE","READY");
        jdbc.update("UPDATE content_revision SET body_markdown=? WHERE id=?","![a](asset:"+first+") ![b](asset:"+second+")",seed.revision());
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0),(?,?,'IMAGE',1)",seed.revision(),second,seed.revision(),first);
        String tag=id();jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,'旧标签','当前说明','DISABLED',2,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tag,author.getId());
        jdbc.update("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(?,?,0)",seed.revision(),tag);
        var hidden=published(author);jdbc.update("UPDATE community_content SET publication_status='WITHDRAWN' WHERE id=?",hidden.content());
        var unready=published(author);String bad=asset(author,"CONTENT_IMAGE","PROCESSING");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)",unready.revision(),bad);
        var response=get(seed.content(),null);assertEquals(200,response.statusCode(),response.body());
        assertEquals(List.of(second,first),JsonPath.read(response.body(),"$.data.imageAssetIds"));
        assertEquals("DISABLED",JsonPath.read(response.body(),"$.data.tags[0].status"));assertEquals(2,JsonPath.<Integer>read(response.body(),"$.data.tags[0].version"));
        assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.author.publishedContentCount"));
    }
    @Test void videoDetailsNeedThePublishedVideoAndAnEmptyBodyHasAnEmptySummary() throws Exception {
        var author=author();var seed=published(author);String video=asset(author,"CONTENT_VIDEO","READY");
        jdbc.update("UPDATE community_content SET kind='VIDEO' WHERE id=?",seed.content());
        jdbc.update("UPDATE content_revision SET body_markdown='' WHERE id=?",seed.revision());
        problem(get(seed.content(),null),404,"RESOURCE_NOT_FOUND");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'VIDEO',0)",seed.revision(),video);
        var response=get(seed.content(),null);assertEquals(200,response.statusCode(),response.body());assertEquals(video,JsonPath.read(response.body(),"$.data.videoAssetId"));assertEquals("",JsonPath.read(response.body(),"$.data.summary"));
    }
    @Test void disablingTheAuthorDoesNotSilentlyUnpublishTheContent() throws Exception {
        var author=author();var seed=published(author);jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?",author.getId());assertEquals(200,get(seed.content(),null).statusCode());
    }
    @Test void successfulRealApprovalCanImmediatelyBeReadAnonymously() throws Exception {
        var author=author();var moderator=author();jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,'MODERATOR',UTC_TIMESTAMP(6))",moderator.getId());
        String authorToken=sessions.create(author.getId(),1,"public-test").accessToken(),modToken=sessions.create(moderator.getId(),1,"public-test").accessToken();
        var created=post("/api/v1/creator/contents",authorToken,Map.of("kind","ARTICLE","title","真实发布","bodyMarkdown","发布正文"));assertEquals(201,created.statusCode(),created.body());
        String content=JsonPath.read(created.body(),"$.data.id"),revision=JsonPath.read(created.body(),"$.data.workingRevision.id");
        var submitted=post("/api/v1/creator/contents/"+content+"/reviews",authorToken,Map.of("expectedVersion",1,"revisionId",revision));assertEquals(201,submitted.statusCode(),submitted.body());
        String review=JsonPath.read(submitted.body(),"$.data.review.id");
        var approved=post("/api/v1/admin/reviews/"+review+"/decisions",modToken,Map.of("expectedContentVersion",2,"expectedReviewVersion",1,"decision","APPROVE","reason","通过"));assertEquals(200,approved.statusCode(),approved.body());
        var response=get(content,null);assertEquals(200,response.statusCode(),response.body());assertEquals("真实发布",JsonPath.read(response.body(),"$.data.title"));
    }
    @Test void invalidParametersDoNotConsumeTheIndependentPublicQuotaBut404Does() throws Exception {
        var seed=published(author());for(int i=0;i<3;i++)problem(get("bad.id",null),422,"VALIDATION_ERROR");
        String source=PREFIX+"{content-public-query}:source:"+com.freecontent.community.auth.security.TokenDigest.sha256("127.0.0.1");
        assertNull(redis.opsForValue().get(source));problem(get(id(),null),404,"RESOURCE_NOT_FOUND");assertEquals("1",redis.opsForValue().get(source));
        assertEquals(200,get(seed.content(),null).statusCode());
    }
    @ParameterizedTest @ValueSource(strings={"ROOT","ASSETS","TAGS","COUNT","AUTHOR","REDIS"})
    void dependencyFailuresReturn503WithoutInternalDetails(String dependency)throws Exception{
        var seed=published(author());var failure=new TransientDataAccessResourceException("sensitive SQL detail");
        switch(dependency){
            case "ROOT" -> doThrow(failure).when(reads).findById(anyString());
            case "ASSETS" -> doThrow(failure).when(reads).findAssets(anyString());
            case "TAGS" -> doThrow(failure).when(reads).findTags(anyString());
            case "COUNT" -> doThrow(failure).when(reads).countVisibleByAuthor(anyString());
            case "AUTHOR" -> doThrow(failure).when(profiles).findById(anyString());
            default -> doThrow(new RedisConnectionFailureException("sensitive Redis detail")).when(redis).execute(any(RedisScript.class),anyList(),any());
        }
        var response=get(seed.content(),null);problem(response,503,"DEPENDENCY_UNAVAILABLE");assertFalse(response.body().contains("sensitive"));
    }
    @Test void aMissingBoundPublishedRevisionIs503()throws Exception{
        var seed=published(author());var row=reads.findById(seed.content());
        doReturn(new PublicContentMapper.PublishedRow(row.id(),row.authorId(),row.kind(),row.lifecycle(),row.publicationStatus(),row.publishedRevisionId(),
                row.firstPublishedAt(),row.publishedAt(),null,null,null,null)).when(reads).findById(seed.content());
        problem(get(seed.content(),null),503,"DEPENDENCY_UNAVAILABLE");
    }
    @Test void publicDataAndAuthorCountStayInOneSnapshotDuringMediaChanges()throws Exception{
        var author=author();var seed=published(author);String asset=asset(author,"CONTENT_IMAGE","READY");
        jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'COVER',0)",seed.revision(),asset);
        var switched=new AtomicBoolean();var pool=Executors.newSingleThreadExecutor();
        try{
            doAnswer(call->{var result=sqlSession.selectList(PublicContentMapper.class.getName()+".findAssets",Map.of("revision",seed.revision()));if(switched.compareAndSet(false,true))pool.submit(()->jdbc.update("UPDATE media_asset SET state='FAILED' WHERE id=?",asset)).get(5,TimeUnit.SECONDS);return result;}).when(reads).findAssets(seed.revision());
            var response=get(seed.content(),null);assertEquals(200,response.statusCode(),response.body());assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.author.publishedContentCount"));
            problem(get(seed.content(),null),404,"RESOURCE_NOT_FOUND");
        }finally{pool.shutdownNow();}
    }
    @Test void publicOpenApiMatchesTheSchemaAndOptionalAuthenticationContract()throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
        Map<String,Object> operation=JsonPath.read(response.body(),"$.paths['/api/v1/contents/{contentId}'].get");
        assertEquals(List.of(Map.of(),Map.of("bearerAuth",List.of())),operation.get("security"));
        Map<String,Object> schemas=JsonPath.read(response.body(),"$.components.schemas");
        try(var input=getClass().getResourceAsStream("/contracts/public-content-schemas.json")){
            assertNotNull(input);Map<String,Object> expected=JsonPath.parse(input).read("$");
            for(String name:List.of("PublicContent","PublicUser","Tag")){
                Map<?,?> schema=(Map<?,?>)schemas.get(name),baseline=(Map<?,?>)expected.get(name);
                assertNotNull(schema);assertEquals(new HashSet<>((List<?>)baseline.get("required")),new HashSet<>((List<?>)schema.get("required")));
                assertEquals(((Map<?,?>)baseline.get("properties")).keySet(),((Map<?,?>)schema.get("properties")).keySet());
            }
        }
        var result=get(published(author()).content(),null);assertEquals(200,result.statusCode());
        assertEquals(Set.of("id","kind","title","summary","author","coverAssetId","tags","firstPublishedAt","publishedAt","publishedRevisionId","likeCount","commentCount","viewerHasLiked","viewerHasBookmarked","bodyMarkdown","imageAssetIds","videoAssetId"),JsonPath.<Map<String,Object>>read(result.body(),"$.data").keySet());
        assertEquals(Set.of("id","displayName","bio","avatarUrl","createdAt","publishedContentCount","followerCount"),JsonPath.<Map<String,Object>>read(result.body(),"$.data.author").keySet());
    }
    private Seed published(UserAccount author){String content=id(),revision=id();
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",content,author.getId());
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,1,'APPROVED','发布标题','# 正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",revision,content);
        jdbc.update("UPDATE community_content SET published_revision_id=?,publication_status='PUBLISHED',first_published_at='2026-11-03 08:00:00',published_at='2026-11-04 08:00:00' WHERE id=?",revision,content);return new Seed(content,revision);}
    private UserAccount author(){var user=LoginTestSupport.account();user.setId(id());user.setEmail("public-"+user.getId()+"@example.test");user.setEmailNormalized(user.getEmail());owners.add(user.getId());users.insert(user);roles.insertDefaultRole(user.getId(),user.getCreatedAt());return user;}
    private String asset(UserAccount owner,String scene,String state){var asset=new MediaAsset();asset.setId(id());asset.setOwnerId(owner.getId());asset.setScene(scene);asset.setState(state);asset.setFileName("test.png");asset.setDeclaredContentType(scene.equals("CONTENT_VIDEO")?"video/mp4":"image/png");asset.setDeclaredSizeBytes(1L);asset.setStorageKey("private/"+owner.getId()+"/"+asset.getId()+"/source");asset.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));media.insert(asset);return asset.getId();}
    private List<Object> rows(Seed seed){return List.of(jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",seed.content()),jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",seed.revision()),jdbc.queryForList("SELECT * FROM content_review_outbox WHERE content_id=?",seed.content()));}
    private HttpResponse<String> get(String content,String token)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/contents/"+content)).timeout(Duration.ofSeconds(15)).GET();if(token!=null)builder.header("Authorization","Bearer "+token);return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> post(String path,String token,Map<String,Object> body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token).header("Idempotency-Key",id()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private void problem(HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals(code,JsonPath.read(response.body(),"$.code"));assertFalse(JsonPath.<Map<String,Object>>read(response.body(),"$").containsKey("data"));}
    private String id(){return UUID.randomUUID().toString().replace("-","");}
    private record Seed(String content,String revision){}
}
