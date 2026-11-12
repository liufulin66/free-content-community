package com.freecontent.community.content.workspace.service;

import com.freecontent.community.content.workspace.dto.EditDraftRequest;
import com.freecontent.community.content.support.ContentTestSupport;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.workspace.mapper.ContentDraftSaveMapper;
import com.freecontent.community.content.workspace.ratelimit.ContentDraftSaveRateLimiter;
import com.freecontent.community.content.workspace.service.transaction.ContentWorkspaceTransactionService;
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
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ContentDraftSaveFlowIT {
    private static final String PREFIX = "test:draft-save:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private ContentDraftSaveMapper writes;
    @MockitoSpyBean private ContentDraftSaveRateLimiter limiter;
    @MockitoSpyBean private ContentWorkspaceTransactionService workspaces;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.query.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.draft-save.redis-key-prefix", () -> PREFIX);
        registry.add("app.content.draft-save.user-per-minute-limit", () -> 3);
    }

    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            ContentTestSupport.clearReviews(jdbc,owner);
            jdbc.update("DELETE FROM content_creation_request WHERE created_by=?", owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
            jdbc.update("DELETE FROM community_tag WHERE created_by=?", owner);
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test void savesTheCurrentDraftWithoutChangingItsIdentityOrOmittedFields() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token, Map.of("kind","ARTICLE","title","Original","bodyMarkdown","Keep this body"));
        assertEquals(201, created.statusCode(), created.body());
        String contentId = JsonPath.read(created.body(), "$.data.id"), revisionId = JsonPath.read(created.body(), "$.data.workingRevision.id");

        var saved = patch(contentId, token, "{\"expectedVersion\":1,\"title\":\"Edited\"}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(2, JsonPath.<Integer>read(saved.body(), "$.data.version"));
        assertEquals(revisionId, JsonPath.read(saved.body(), "$.data.workingRevision.id"));
        assertEquals(1, JsonPath.<Integer>read(saved.body(), "$.data.workingRevision.revisionNumber"));
        assertEquals("Edited", JsonPath.read(saved.body(), "$.data.workingRevision.title"));
        assertEquals("Keep this body", JsonPath.read(saved.body(), "$.data.workingRevision.bodyMarkdown"));
        assertEquals("UNPUBLISHED", JsonPath.read(saved.body(), "$.data.publicationStatus"));
        assertEquals("DRAFT", JsonPath.read(saved.body(), "$.data.workingRevision.status"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE content_id=?", Integer.class, contentId));
        var current = get(contentId, token); assertEquals(200, current.statusCode(), current.body());
        assertEquals(JsonPath.<Map<String,Object>>read(saved.body(),"$.data"), JsonPath.read(current.body(),"$.data"));
    }

    @ParameterizedTest @ValueSource(strings = {"title","bodyMarkdown","coverAssetId","imageAssetIds","videoAssetId","tagIds"})
    void sameValuesLeaveTheVersionAndAllTimestampsUnchanged(String field) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token,Map.of("kind","ARTICLE","title","Original","bodyMarkdown","Original body"));
        String contentId = JsonPath.read(created.body(),"$.data.id"), revisionId = JsonPath.read(created.body(),"$.data.workingRevision.id");
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",contentId);
        var draftBefore = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",revisionId);
        var input = new LinkedHashMap<String,Object>(); input.put("expectedVersion",1);
        input.put(field,switch(field) { case "title" -> "Original"; case "bodyMarkdown" -> "Original body"; case "imageAssetIds","tagIds" -> List.of(); default -> null; });

        var response = patch(contentId,token,json.writeValueAsString(input));

        assertEquals(200,response.statusCode(),response.body()); assertEquals(1,JsonPath.<Integer>read(response.body(),"$.data.version"));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",contentId));
        assertEquals(draftBefore,jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",revisionId));
    }

    @Test void staleVersionsConflictEvenWhenTheRequestedValueAlreadyMatches() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token,Map.of("kind","ARTICLE")).body(),"$.data.id");
        assertEquals(200,patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Edited\"}").statusCode());
        var response = patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Edited\"}");
        assertProblem(response,409,"CONTENT_VERSION_CONFLICT"); assertEquals(2,JsonPath.<Integer>read(response.body(),"$.currentVersion"));
    }

    @Test void theIntegerVersionLimitAllowsNoOpsButNeverOverflowsOnChanges() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token,Map.of("kind","ARTICLE","title","Same")).body(),"$.data.id");
        jdbc.update("UPDATE community_content SET version=2147483647 WHERE id=?",contentId);
        var same = patch(contentId,token,"{\"expectedVersion\":2147483647,\"title\":\"Same\"}");
        assertEquals(200,same.statusCode(),same.body());
        assertProblem(patch(contentId,token,"{\"expectedVersion\":2147483647,\"title\":\"Changed\"}"),409,"CONTENT_VERSION_CONFLICT");
        assertEquals(Integer.MAX_VALUE,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
    }

    private void assertProblem(HttpResponse<String> response,int status,String code) {
        assertEquals(status,response.statusCode(),response.body()); assertEquals(code,JsonPath.read(response.body(),"$.code"));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    @ParameterizedTest @ValueSource(strings = {"ARTICLE","VIDEO"})
    void replacesAndClearsReferencesAtomicallyWhileKeepingTheSameDraft(String kind) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String oldImage = asset(owner,"CONTENT_IMAGE","WAITING_UPLOAD"), nextImage = asset(owner,"CONTENT_IMAGE","PROCESSING"), cover = asset(owner,"CONTENT_IMAGE","FAILED");
        String oldTag = tag(owner), nextTag = tag(owner), lastTag = tag(owner);
        var input = new LinkedHashMap<String,Object>(); input.put("kind",kind); input.put("title","Old");
        input.put("bodyMarkdown","![old](asset:"+oldImage+")"); input.put("coverAssetId",oldImage); input.put("imageAssetIds",List.of(oldImage)); input.put("tagIds",List.of(oldTag));
        if (kind.equals("VIDEO")) input.put("videoAssetId",asset(owner,"CONTENT_VIDEO","PROCESSING"));
        var created = create(token,input); assertEquals(201,created.statusCode(),created.body());
        String contentId = JsonPath.read(created.body(),"$.data.id"), revisionId = JsonPath.read(created.body(),"$.data.workingRevision.id");
        var changes = new LinkedHashMap<String,Object>(); changes.put("expectedVersion",1); changes.put("title","New");
        changes.put("bodyMarkdown","![cover](asset:"+cover+") ![next](asset:"+nextImage+")"); changes.put("imageAssetIds",List.of(cover,nextImage));
        changes.put("coverAssetId",cover); changes.put("tagIds",List.of(lastTag,nextTag));
        changes.put("videoAssetId",kind.equals("VIDEO") ? asset(owner,"CONTENT_VIDEO","FAILED") : null);

        var saved = patch(contentId,token,json.writeValueAsString(changes));

        assertEquals(200,saved.statusCode(),saved.body()); assertEquals(2,JsonPath.<Integer>read(saved.body(),"$.data.version"));
        assertEquals(List.of(cover,nextImage),JsonPath.read(saved.body(),"$.data.workingRevision.imageAssetIds"));
        assertEquals(List.of(lastTag,nextTag),JsonPath.read(saved.body(),"$.data.workingRevision.tagIds"));
        assertEquals(cover,JsonPath.read(saved.body(),"$.data.workingRevision.coverAssetId"));
        assertEquals(changes.get("videoAssetId"),JsonPath.read(saved.body(),"$.data.workingRevision.videoAssetId"));
        assertEquals(revisionId,JsonPath.read(saved.body(),"$.data.workingRevision.id"));
        changes.put("expectedVersion",2); changes.put("title",""); changes.put("bodyMarkdown","");
        changes.put("imageAssetIds",List.of()); changes.put("tagIds",List.of()); changes.put("coverAssetId",null); changes.put("videoAssetId",null);
        var cleared = patch(contentId,token,json.writeValueAsString(changes));
        assertEquals(200,cleared.statusCode(),cleared.body()); assertEquals(3,JsonPath.<Integer>read(cleared.body(),"$.data.version"));
        assertEquals("",JsonPath.read(cleared.body(),"$.data.workingRevision.title"));
        assertNull(JsonPath.read(cleared.body(),"$.data.workingRevision.coverAssetId")); assertNull(JsonPath.read(cleared.body(),"$.data.workingRevision.videoAssetId"));
        assertEquals(List.of(),JsonPath.read(cleared.body(),"$.data.workingRevision.imageAssetIds")); assertEquals(List.of(),JsonPath.read(cleared.body(),"$.data.workingRevision.tagIds"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_revision_asset WHERE revision_id=?",Integer.class,revisionId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_revision_tag WHERE revision_id=?",Integer.class,revisionId));
    }

    @ParameterizedTest @ValueSource(strings = {"COVER_MISSING","IMAGE_OTHER","VIDEO_OTHER","TAG_MISSING","COVER_SCENE","IMAGE_SCENE","VIDEO_SCENE"})
    void rejectsMissingForeignOrWrongPurposeReferencesWithoutChangingTheDraft(String scenario) throws Exception {
        UserAccount owner = fixture(),other = fixture(); String token = token(owner);
        var created = create(token,Map.of("kind",scenario.startsWith("VIDEO") ? "VIDEO" : "ARTICLE"));
        String contentId = JsonPath.read(created.body(),"$.data.id"); var changes = new LinkedHashMap<String,Object>(); changes.put("expectedVersion",1);
        switch(scenario) {
            case "COVER_MISSING" -> changes.put("coverAssetId",id());
            case "IMAGE_OTHER","IMAGE_SCENE" -> {
                String image = asset(scenario.equals("IMAGE_OTHER") ? other : owner,scenario.equals("IMAGE_OTHER") ? "CONTENT_IMAGE" : "AVATAR","PROCESSING");
                changes.put("bodyMarkdown","![x](asset:"+image+")"); changes.put("imageAssetIds",List.of(image));
            }
            case "VIDEO_OTHER" -> changes.put("videoAssetId",asset(other,"CONTENT_VIDEO","PROCESSING"));
            case "COVER_SCENE" -> changes.put("coverAssetId",asset(owner,"REPORT_ATTACHMENT","READY"));
            case "VIDEO_SCENE" -> changes.put("videoAssetId",asset(owner,"CONTENT_IMAGE","READY"));
            default -> changes.put("tagIds",List.of(id()));
        }
        var response = patch(contentId,token,json.writeValueAsString(changes));
        assertProblem(response,scenario.endsWith("SCENE") ? 422 : 404,scenario.endsWith("SCENE") ? "VALIDATION_ERROR" : "RESOURCE_NOT_FOUND");
        assertEquals(1,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
    }

    private String asset(UserAccount owner,String scene,String state) {
        String assetId = id(); String type = scene.equals("CONTENT_VIDEO") ? "video/mp4" : "image/png";
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,created_at) VALUES(?,?,?,?,?,1,?,?,UTC_TIMESTAMP(6))",
                assetId,owner.getId(),scene,"fixture",type,"private/"+owner.getId()+"/"+assetId+"/source",state); return assetId;
    }

    @ParameterizedTest @ValueSource(strings = {"USER","SOURCE"})
    void noOpsAndVersionConflictsConsumeIndependentDraftSaveQuota(String scope) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token,Map.of("kind","ARTICLE","title","Same")).body(),"$.data.id");
        assertEquals(200,patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Same\"}").statusCode());
        if (scope.equals("USER")) {
            assertProblem(patch(contentId,token,"{\"expectedVersion\":2,\"title\":\"X\"}"),409,"CONTENT_VERSION_CONFLICT");
            assertEquals(200,patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Same\"}").statusCode());
        } else redis.opsForValue().set(PREFIX+"{content-draft-save}:source:"+TokenDigest.sha256("127.0.0.1"),"120",Duration.ofMinutes(1));
        var limited = patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Change\"}");
        assertProblem(limited,429,"RATE_LIMITED"); assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        assertEquals(1,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
        assertEquals("1",redis.opsForValue().get(PREFIX+"{content-create}:user:"+TokenDigest.sha256(owner.getId())));
        assertTrue(redis.keys(PREFIX+"{content-query}:*").isEmpty());
    }

    @Test void invalidMergedInputDoesNotConsumeQuotaOrChangeAnyBusinessData() throws Exception {
        UserAccount owner = fixture(); String token = token(owner), image = asset(owner,"CONTENT_IMAGE","WAITING_UPLOAD");
        var created = create(token,Map.of("kind","ARTICLE","bodyMarkdown","![x](asset:"+image+")","imageAssetIds",List.of(image)));
        String contentId = JsonPath.read(created.body(),"$.data.id");
        assertProblem(patch(contentId,token,"{\"expectedVersion\":1,\"bodyMarkdown\":\"No images\"}"),422,"VALIDATION_ERROR");
        assertProblem(patch(contentId,token,"{\"expectedVersion\":1,\"tagIds\":[\"tag\",\"tag\"]}"),422,"VALIDATION_ERROR");
        String wrong = asset(owner,"AVATAR","READY");
        assertProblem(patch(contentId,token,json.writeValueAsString(Map.of("expectedVersion",1,"coverAssetId",wrong))),422,"VALIDATION_ERROR");
        assertTrue(redis.keys(PREFIX+"{content-draft-save}:*").isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION","UNVERIFIED","DISABLED","WRITE_BLOCKED","NO_USER"})
    void eligibilityIsRecheckedAfterAuthenticationAndAfterPreflight(String change) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token,Map.of("kind","ARTICLE")).body(),"$.data.id");
        clearInvocations(writes);
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod(); changeAccount(owner,change); return principal;
        }).when(authentication).authenticate(token);
        var response = patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Forbidden\"}");
        assertProblem(response,change.equals("AUTH_VERSION") ? 401 : 403,eligibilityError(change)); verifyNoInteractions(writes);
        assertEquals(1,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION","UNVERIFIED","DISABLED","WRITE_BLOCKED","NO_USER"})
    void losingEligibilityInTheGapPreventsTheFinalCommit(String change) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token,Map.of("kind","ARTICLE")).body(),"$.data.id");
        doAnswer(invocation -> { invocation.callRealMethod(); changeAccount(owner,change); return null; }).when(limiter).acquire(owner.getId(),"127.0.0.1");
        var response = patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Forbidden\"}");
        assertProblem(response,change.equals("AUTH_VERSION") ? 401 : 403,eligibilityError(change));
        assertEquals(1,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
    }

    @ParameterizedTest @ValueSource(strings = {"USER","MODERATOR","ADMIN"})
    void otherAuthorsNeverReceiveVersionInformationOrWriteAccess(String role) throws Exception {
        UserAccount owner = fixture(),other = fixture();
        if (!role.equals("USER")) jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",other.getId(),role);
        String contentId = JsonPath.read(create(token(owner),Map.of("kind","ARTICLE")).body(),"$.data.id");
        var response = patch(contentId,token(other),"{\"expectedVersion\":99,\"title\":\"Forbidden\"}");
        assertProblem(response,404,"RESOURCE_NOT_FOUND"); assertFalse(JsonPath.<Map<String,Object>>read(response.body(),"$").containsKey("currentVersion"));
        assertEquals(1,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
    }

    @ParameterizedTest @ValueSource(strings = {"IN_REVIEW","APPROVED","REJECTED","CANCELLED","NONE","DELETED","ACTIVE_REVIEW"})
    void onlyAnActiveContentsCurrentDraftCanBeSavedEvenForNoOps(String state) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token,Map.of("kind","ARTICLE","title","Same"));
        String contentId = JsonPath.read(created.body(),"$.data.id"),revision = JsonPath.read(created.body(),"$.data.workingRevision.id");
        switch(state) {
            case "NONE" -> { jdbc.update("UPDATE content_revision SET status='REJECTED' WHERE id=?",revision); jdbc.update("UPDATE community_content SET working_revision_id=NULL WHERE id=?",contentId); }
            case "DELETED" -> jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?",contentId);
            case "ACTIVE_REVIEW" -> jdbc.update("UPDATE community_content SET active_review_id=? WHERE id=?",ContentTestSupport.pending(jdbc,contentId,revision),contentId);
            default -> jdbc.update("UPDATE content_revision SET status=? WHERE id=?",state,revision);
        }
        var before = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",revision);
        assertProblem(patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Same\"}"),409,state.equals("DELETED") ? "CONTENT_NOT_EDITABLE" : "DRAFT_NOT_EDITABLE");
        assertEquals(before,jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",revision));
    }

    @ParameterizedTest @ValueSource(strings = {"PUBLISHED","WITHDRAWN","TAKEN_DOWN"})
    void savingAnUpdateOrRemediationDraftNeverChangesThePublishedSnapshotOrVisibility(String publication) throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token,Map.of("kind","ARTICLE","title","Public snapshot"));
        String contentId = JsonPath.read(created.body(),"$.data.id"),published = JsonPath.read(created.body(),"$.data.workingRevision.id"),draft = id();
        jdbc.update("UPDATE content_revision SET status='APPROVED' WHERE id=?",published);
        jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) VALUES(?,?,2,'DRAFT','New draft','',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",draft,contentId);
        jdbc.update("UPDATE community_content SET working_revision_id=?,published_revision_id=?,publication_status=?,version=5 WHERE id=?",draft,published,publication,contentId);
        var original = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",published);
        var snapshot = jdbc.queryForMap("SELECT * FROM content_creation_request WHERE created_by=?",owner.getId());
        var response = patch(contentId,token,"{\"expectedVersion\":5,\"title\":\"Edited update\"}");
        assertEquals(200,response.statusCode(),response.body()); assertEquals(6,JsonPath.<Integer>read(response.body(),"$.data.version"));
        assertEquals(publication,JsonPath.read(response.body(),"$.data.publicationStatus"));
        assertEquals("Public snapshot",JsonPath.read(response.body(),"$.data.publishedRevision.title"));
        assertEquals(draft,JsonPath.read(response.body(),"$.data.workingRevision.id"));
        assertEquals(original,jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",published));
        assertEquals(snapshot,jdbc.queryForMap("SELECT * FROM content_creation_request WHERE created_by=?",owner.getId()));
    }

    @Test void reorderedArraysAreRealChangesAndTheMergedBodyUsesExistingImages() throws Exception {
        UserAccount owner = fixture(); String token = token(owner),a = asset(owner,"CONTENT_IMAGE","PROCESSING"),b = asset(owner,"CONTENT_IMAGE","READY");
        String first = tag(owner),second = tag(owner);
        var created = create(token,Map.of("kind","ARTICLE","bodyMarkdown","![a](asset:"+a+") ![b](asset:"+b+")","imageAssetIds",List.of(a,b),"tagIds",List.of(first,second)));
        String contentId = JsonPath.read(created.body(),"$.data.id");
        var response = patch(contentId,token,json.writeValueAsString(Map.of("expectedVersion",1,"imageAssetIds",List.of(b,a),"tagIds",List.of(second,first))));
        assertEquals(200,response.statusCode(),response.body()); assertEquals(2,JsonPath.<Integer>read(response.body(),"$.data.version"));
        assertEquals(List.of(b,a),JsonPath.read(response.body(),"$.data.workingRevision.imageAssetIds"));
        assertEquals(List.of(second,first),JsonPath.read(response.body(),"$.data.workingRevision.tagIds"));
    }

    @Test void concurrentSavesWithTheSameVersionHaveExactlyOneWinner() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token,Map.of("kind","ARTICLE")).body(),"$.data.id");
        var ready = new CyclicBarrier(2);
        doAnswer(invocation -> { invocation.callRealMethod(); ready.await(10,TimeUnit.SECONDS); return null; }).when(limiter).acquire(owner.getId(),"127.0.0.1");
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"First\"}"));
            var b = workers.submit(() -> patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Second\"}"));
            var first = a.get(20,TimeUnit.SECONDS); var second = b.get(20,TimeUnit.SECONDS);
            assertEquals(Set.of(200,409),Set.of(first.statusCode(),second.statusCode()));
            var conflict = first.statusCode() == 409 ? first : second; assertProblem(conflict,409,"CONTENT_VERSION_CONFLICT");
            assertEquals(2,JsonPath.<Integer>read(conflict.body(),"$.currentVersion"));
            assertEquals(2,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(15,TimeUnit.SECONDS)); }
    }

    @Test void anEditCommittedBetweenPreflightAndWriteIsNotOverwritten() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        var created = create(token,Map.of("kind","ARTICLE")); String contentId = JsonPath.read(created.body(),"$.data.id"),revision = JsonPath.read(created.body(),"$.data.workingRevision.id");
        doAnswer(invocation -> {
            invocation.callRealMethod(); new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE content_revision SET title='Other edit' WHERE id=?",revision);
                jdbc.update("UPDATE community_content SET version=2 WHERE id=?",contentId);
            }); return null;
        }).when(limiter).acquire(owner.getId(),"127.0.0.1");
        assertProblem(patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Overwrite\"}"),409,"CONTENT_VERSION_CONFLICT");
        assertEquals("Other edit",jdbc.queryForObject("SELECT title FROM content_revision WHERE id=?",String.class,revision));
    }

    @ParameterizedTest @ValueSource(strings = {"VERSION","REVISION","ASSET","TAG","RESULT"})
    void failuresAtEveryWriteStageRollBackTheEntireChange(String stage) throws Exception {
        UserAccount owner = fixture(); String token = token(owner),old = asset(owner,"CONTENT_IMAGE","PROCESSING"),next = asset(owner,"CONTENT_IMAGE","READY");
        var created = create(token,Map.of("kind","ARTICLE","bodyMarkdown","![x](asset:"+old+")","imageAssetIds",List.of(old),"tagIds",List.of(tag(owner))));
        String contentId = JsonPath.read(created.body(),"$.data.id"),revision = JsonPath.read(created.body(),"$.data.workingRevision.id");
        var before = jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",contentId);
        var draftBefore = jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",revision);
        var imagesBefore = jdbc.queryForList("SELECT * FROM content_revision_asset WHERE revision_id=? ORDER BY usage_type,position",revision);
        var tagsBefore = jdbc.queryForList("SELECT * FROM content_revision_tag WHERE revision_id=? ORDER BY position",revision);
        var failure = new TransientDataAccessResourceException("sensitive database detail");
        switch(stage) {
            case "VERSION" -> doThrow(failure).when(writes).advanceVersion(anyString(),anyString(),anyInt(),anyString(),any());
            case "REVISION" -> doThrow(failure).when(writes).updateDraft(anyString(),anyString(),any(),any());
            case "ASSET" -> doThrow(failure).when(writes).insertAsset(anyString(),anyString(),anyString(),anyInt());
            case "TAG" -> doThrow(failure).when(writes).insertTag(anyString(),anyString(),anyInt());
            default -> doAnswer(invocation -> {
                if (jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId) == 2) throw failure;
                return invocation.callRealMethod();
            }).when(workspaces).read(eq(contentId),any());
        }
        var input = Map.of("expectedVersion",1,"title","Changed","bodyMarkdown","![x](asset:"+next+")","imageAssetIds",List.of(next),"tagIds",List.of(tag(owner)));
        var response = patch(contentId,token,json.writeValueAsString(input)); assertProblem(response,503,"DEPENDENCY_UNAVAILABLE"); assertFalse(response.body().contains("sensitive database detail"));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM community_content WHERE id=?",contentId));
        assertEquals(draftBefore,jdbc.queryForMap("SELECT * FROM content_revision WHERE id=?",revision));
        assertEquals(imagesBefore,jdbc.queryForList("SELECT * FROM content_revision_asset WHERE revision_id=? ORDER BY usage_type,position",revision));
        assertEquals(tagsBefore,jdbc.queryForList("SELECT * FROM content_revision_tag WHERE revision_id=? ORDER BY position",revision));
    }

    @Test void redisFailureStopsBeforeAnyWriteAndKeepsBusinessDataUnchanged() throws Exception {
        UserAccount owner = fixture(); String token = token(owner);
        String contentId = JsonPath.read(create(token,Map.of("kind","ARTICLE")).body(),"$.data.id");
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX+"{content-draft-save}:")),any(),any());
        var response = patch(contentId,token,"{\"expectedVersion\":1,\"title\":\"Changed\"}"); assertProblem(response,503,"DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive redis detail")); verify(writes,never()).advanceVersion(anyString(),anyString(),anyInt(),anyString(),any());
        assertEquals(1,jdbc.queryForObject("SELECT version FROM community_content WHERE id=?",Integer.class,contentId));
    }

    private void changeAccount(UserAccount owner,String change) {
        switch(change) {
            case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?",owner.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?",owner.getId());
            case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner.getId());
            default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?",change,owner.getId());
        }
    }
    private String eligibilityError(String change) {
        return switch(change) { case "AUTH_VERSION" -> "AUTHENTICATION_REQUIRED"; case "UNVERIFIED","NO_USER" -> "PERMISSION_DENIED"; default -> "ACCOUNT_RESTRICTED"; };
    }

    @Test void swaggerDeclaresPatchPresenceVersionBoundsAndNoIdempotencyHeader() throws Exception {
        var response = send("GET","/v3/api-docs",null,null,null); assertEquals(200,response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(),"$.paths['/api/v1/creator/contents/{contentId}/draft'].patch");
        assertEquals("editContentDraft",operation.get("operationId"));
        List<Map<String,Object>> parameters = (List<Map<String,Object>>) operation.get("parameters");
        assertEquals(1,parameters.size()); assertEquals("contentId",parameters.get(0).get("name")); assertEquals("path",parameters.get(0).get("in"));
        Map<String,Object> schema = JsonPath.read(response.body(),"$.components.schemas.EditDraftRequest");
        assertEquals(List.of("expectedVersion"),schema.get("required")); assertEquals(2,schema.get("minProperties")); assertEquals(false,schema.get("additionalProperties"));
        Map<String,Map<String,Object>> fields = (Map<String,Map<String,Object>>) schema.get("properties");
        assertEquals(Set.of("expectedVersion","title","bodyMarkdown","coverAssetId","imageAssetIds","videoAssetId","tagIds"),fields.keySet());
        assertEquals("integer",fields.get("expectedVersion").get("type")); assertEquals(Integer.MAX_VALUE,((Number) fields.get("expectedVersion").get("maximum")).intValue());
        assertEquals(50,fields.get("imageAssetIds").get("maxItems")); assertEquals(5,fields.get("tagIds").get("maxItems"));
        assertEquals(true,fields.get("imageAssetIds").get("uniqueItems"));
        for (String field : List.of("title","bodyMarkdown","imageAssetIds","tagIds")) assertFalse(fields.get(field).containsKey("default"));
        assertTrue(fields.get("coverAssetId").get("type") instanceof List<?> types && types.contains("null"));
    }
    private String tag(UserAccount owner) {
        String tagId = id(); jdbc.update("INSERT INTO community_tag(id,name,description,status,version,created_by,created_at,updated_at) VALUES(?,?,'','DISABLED',1,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                tagId,"Draft_"+tagId.substring(0,20),owner.getId()); return tagId;
    }

    private UserAccount fixture() {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("draft-save-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        owners.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(),user.getCreatedAt()); return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(),user.getAuthVersion(),"draft-save-test").accessToken(); }
    private HttpResponse<String> create(String token, Map<String,Object> body) throws Exception {
        return send("POST","/api/v1/creator/contents",token,json.writeValueAsString(body),id());
    }
    private HttpResponse<String> patch(String contentId, String token, String body) throws Exception { return send("PATCH","/api/v1/creator/contents/" + contentId + "/draft",token,body,null); }
    private HttpResponse<String> get(String contentId, String token) throws Exception { return send("GET","/api/v1/creator/contents/" + contentId,token,null,null); }
    private HttpResponse<String> send(String method, String path, String token, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization","Bearer "+token);
        if (key != null) request.header("Idempotency-Key",key);
        if (body != null) request.header("Content-Type","application/json");
        request.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String id() { return UUID.randomUUID().toString().replace("-",""); }
}
