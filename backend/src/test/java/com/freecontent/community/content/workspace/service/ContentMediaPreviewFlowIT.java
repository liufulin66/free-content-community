package com.freecontent.community.content.workspace.service;

import com.freecontent.community.content.support.ContentTestSupport;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.storage.UploadSigner;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.media.storage.MediaReadSigner;
import com.freecontent.community.media.entity.MediaUpload;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.workspace.mapper.ContentMediaPreviewMapper;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,properties = {
        "app.auth.email-challenge.delivery-enabled=false","app.media.upload-cleanup.enabled=false"})
class ContentMediaPreviewFlowIT {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AxoAAAAASUVORK5CYII=");
    private static final String PREFIX = "test:media-preview:"+UUID.randomUUID()+":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private StorageProperties storage;
    @Autowired private UploadSigner uploadSigner;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    @MockitoSpyBean private ContentMediaPreviewMapper references;
    @MockitoSpyBean private MediaObjectReader objects;
    @MockitoSpyBean private MediaReadSigner readSigner;
    private final List<String> owners = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @DynamicPropertySource static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix",() -> PREFIX);
        registry.add("app.content.creation.redis-key-prefix",() -> PREFIX);
        registry.add("app.content.query.redis-key-prefix",() -> PREFIX);
        registry.add("app.content.media-preview.redis-key-prefix",() -> PREFIX);
        registry.add("app.content.media-preview.user-per-minute-limit",() -> 3);
    }
    @AfterEach void cleanup() {
        if (!objectKeys.isEmpty()) try (var minio = minio()) {
            for (String key : objectKeys) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
        } catch (Exception ex) { throw new AssertionError("test object cleanup failed",ex); }
        Set<String> keys = redis.keys(PREFIX+"*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            ContentTestSupport.clearReviews(jdbc,owner);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='content_media_preview_audit'",Integer.class) == 1)
                jdbc.update("DELETE FROM content_media_preview_audit WHERE actor_id=?",owner);
            jdbc.update("DELETE FROM content_creation_request WHERE created_by=?",owner);
            ContentTestSupport.clearWorkspace(jdbc, owner);
        }
        // 跨作者攻击用例会故意构造非法引用，全部测试内容删完后才能删除任一作者的素材。
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?",owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner); users.deleteById(owner);
        }
    }

    @Test void referencedButUnreadyMediaReturns409WithoutIssuingAnAccessUrl() throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","bodyMarkdown","![x](asset:"+asset+")","imageAssetIds",List.of(asset)));
        String content = (String)workspace.get("id"),revision = (String)((Map<?,?>)workspace.get("workingRevision")).get("id");

        var response = access(content,revision,asset,token);

        assertProblem(response,409,"MEDIA_NOT_READY");
        assertFalse(JsonPath.<Map<String,Object>>read(response.body(),"$").containsKey("data"));
    }

    @Test void readyResultObjectsGetWorkingPrivateGetUrlsAndMinimalAuditRecords() throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","bodyMarkdown","![x](asset:"+asset+")","coverAssetId",asset,"imageAssetIds",List.of(asset)));
        String content = (String)workspace.get("id"),revision = (String)((Map<?,?>)workspace.get("workingRevision")).get("id");
        ready(owner,asset,PNG,"image/png");

        var response = access(content,revision,asset,token);

        assertEquals(200,response.statusCode()); Map<String,Object> data = JsonPath.read(response.body(),"$.data");
        assertEquals(Set.of("assetId","url","expiresAt","contentType"),data.keySet());
        assertEquals(asset,data.get("assetId")); assertEquals("image/png",data.get("contentType"));
        String url = (String)data.get("url"); assertTrue(url.contains("X-Amz-Expires=300"));
        Instant expires = Instant.parse((String)data.get("expiresAt")); assertTrue(expires.isAfter(Instant.now())); assertTrue(expires.isBefore(Instant.now().plusSeconds(301)));
        var bytes = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,bytes.statusCode()); assertArrayEquals(PNG,bytes.body()); assertEquals("image/png",bytes.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(bytes.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        var wrongMethod = client.send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1})).build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(403,wrongMethod.statusCode());
        var unsigned = client.send(HttpRequest.newBuilder(URI.create(url.substring(0,url.indexOf('?')))).GET().build(),HttpResponse.BodyHandlers.discarding());
        assertTrue(unsigned.statusCode() == 403 || unsigned.statusCode() == 404);
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        var audit = jdbc.queryForMap("SELECT * FROM content_media_preview_audit WHERE actor_id=?",owner.getId());
        assertEquals(content,audit.get("content_id")); assertEquals(revision,audit.get("revision_id")); assertEquals(asset,audit.get("asset_id"));
        assertEquals(JsonPath.read(response.body(),"$.requestId"),audit.get("request_id"));
        assertFalse(audit.values().stream().anyMatch(value -> value instanceof String text && text.contains("X-Amz-")));
    }

    private String ready(UserAccount owner,String asset,byte[] bytes,String contentType) throws Exception {
        String key = "private/"+owner.getId()+"/"+asset+"/ready/"+id(); objectKeys.add(key);
        String etag;
        try (var minio = minio()) {
            var result = minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(key).contentType(contentType)
                    .stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1L).build()); etag = result.etag();
        }
        jdbc.update("UPDATE media_asset SET state='READY',actual_content_type=?,actual_size_bytes=?,ready_object_key=?,ready_object_etag=?,ready_storage_id=? WHERE id=?",
                contentType,bytes.length,key,etag,uploadSigner.identity(),asset);
        return key;
    }
    private MinioClient minio() { return MinioClient.builder().endpoint(storage.endpoint()).region(storage.region()).credentials(storage.accessKey(),storage.secretKey()).build(); }

    @ParameterizedTest @ValueSource(strings = {"WAITING_UPLOAD","PROCESSING","FAILED"})
    void unreadyStatesDoNotTouchStorageOrCreateAnAudit(String state) throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE",state);
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset));
        assertProblem(access(content(workspace),revision(workspace),asset,token),409,"MEDIA_NOT_READY");
        verifyNoInteractions(objects,readSigner); assertEquals(0,auditCount(owner));
    }

    @ParameterizedTest @ValueSource(strings = {"UNREFERENCED","OTHER_ASSET_OWNER","OTHER_CONTENT_OWNER","WRONG_REVISION","WRONG_SCENE"})
    void ownershipRevisionMembershipAndActualReferenceAreAllRequiredBeforeReadiness(String scenario) throws Exception {
        UserAccount owner = fixture(),other = fixture(); String token = token(owner);
        String asset = asset(scenario.equals("OTHER_ASSET_OWNER") ? other : owner,scenario.equals("WRONG_SCENE") ? "AVATAR" : "CONTENT_IMAGE","PROCESSING");
        var workspace = create(scenario.equals("OTHER_CONTENT_OWNER") ? token(other) : token,Map.of("kind","ARTICLE"));
        String content = content(workspace),revision = revision(workspace);
        if (!scenario.equals("UNREFERENCED")) jdbc.update("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(?,?,'IMAGE',0)",revision,asset);
        if (scenario.equals("WRONG_REVISION")) {
            var another = create(token,Map.of("kind","ARTICLE")); content = content(another);
        }
        assertProblem(access(content,revision,asset,token),404,"RESOURCE_NOT_FOUND");
        verifyNoInteractions(objects,readSigner); assertEquals(0,auditCount(owner));
    }

    @ParameterizedTest @ValueSource(strings = {"DRAFT","IN_REVIEW","APPROVED","REJECTED","CANCELLED","DELETED"})
    void anyOwnedReferencedRevisionCanBePreviewedIncludingHistoricalAndRecycleBinContent(String state) throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset));
        String content = content(workspace),revision = revision(workspace); ready(owner,asset,PNG,"image/png");
        if (state.equals("DELETED")) jdbc.update("UPDATE community_content SET lifecycle='DELETED',deleted_at=UTC_TIMESTAMP(6) WHERE id=?",content);
        else if (!state.equals("DRAFT")) {
            jdbc.update("UPDATE content_revision SET status=? WHERE id=?",state,revision);
            if (state.equals("IN_REVIEW")) jdbc.update("UPDATE community_content SET active_review_id=? WHERE id=?",ContentTestSupport.pending(jdbc,content,revision),content);
            else jdbc.update("UPDATE community_content SET working_revision_id=NULL,published_revision_id=?,publication_status=? WHERE id=?",
                    state.equals("APPROVED") ? revision : null,state.equals("APPROVED") ? "PUBLISHED" : "UNPUBLISHED",content);
        }
        assertEquals(200,access(content,revision,asset,token).statusCode()); assertEquals(1,auditCount(owner));
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION","UNVERIFIED","DISABLED","NO_USER","WRITE_BLOCKED"})
    void permissionsAreRecheckedAfterAuthenticationAndBlockedAccountsRetainReadAccess(String change) throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset)); ready(owner,asset,PNG,"image/png"); clearInvocations(references);
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal)invocation.callRealMethod(); changeAccount(owner,change); return principal;
        }).when(authentication).authenticate(token);
        var response = access(content(workspace),revision(workspace),asset,token);
        if (change.equals("WRITE_BLOCKED")) assertEquals(200,response.statusCode());
        else { assertProblem(response,change.equals("AUTH_VERSION") ? 401 : 403,permissionError(change)); verifyNoInteractions(references,objects,readSigner); assertEquals(0,auditCount(owner)); }
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH_VERSION","UNVERIFIED","DISABLED","NO_USER","WRITE_BLOCKED","REFERENCE_REMOVED","FAILED","BINDING_CHANGED"})
    void storageIsCheckedOutsideTransactionsAndFinalAuthorizationPreventsStaleGrants(String change) throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset)); ready(owner,asset,PNG,"image/png");
        String content = content(workspace),revision = revision(workspace);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); invocation.callRealMethod();
            switch(change) {
                case "REFERENCE_REMOVED" -> jdbc.update("DELETE FROM content_revision_asset WHERE revision_id=?",revision);
                case "FAILED" -> jdbc.update("UPDATE media_asset SET state='FAILED' WHERE id=?",asset);
                case "BINDING_CHANGED" -> jdbc.update("UPDATE media_asset SET ready_object_key=? WHERE id=?","private/"+owner.getId()+"/"+asset+"/ready/"+id(),asset);
                default -> changeAccount(owner,change);
            }
            return null;
        }).when(objects).verify(any());
        var response = access(content,revision,asset,token);
        if (change.equals("WRITE_BLOCKED")) { assertEquals(200,response.statusCode()); assertEquals(1,auditCount(owner)); }
        else {
            switch(change) {
                case "REFERENCE_REMOVED" -> assertProblem(response,404,"RESOURCE_NOT_FOUND");
                case "FAILED" -> assertProblem(response,409,"MEDIA_NOT_READY");
                case "BINDING_CHANGED" -> assertProblem(response,503,"DEPENDENCY_UNAVAILABLE");
                default -> assertProblem(response,change.equals("AUTH_VERSION") ? 401 : 403,permissionError(change));
            }
            assertEquals(0,auditCount(owner)); verifyNoInteractions(readSigner);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"NO_BINDING","MISSING_OBJECT","ETAG","SIZE","STORAGE","SOURCE_KEY","OTHER_ASSET_KEY","BAD_TYPE","SCENE_TYPE"})
    void invalidOrUnavailableReadyBindingsNeverIssueUrls(String scenario) throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset)); String key = ready(owner,asset,PNG,"image/png");
        switch(scenario) {
            case "NO_BINDING" -> jdbc.update("UPDATE media_asset SET ready_object_key=NULL,ready_object_etag=NULL,ready_storage_id=NULL WHERE id=?",asset);
            case "MISSING_OBJECT" -> { try (var minio = minio()) { minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build()); } }
            case "ETAG" -> jdbc.update("UPDATE media_asset SET ready_object_etag='wrong' WHERE id=?",asset);
            case "SIZE" -> jdbc.update("UPDATE media_asset SET actual_size_bytes=actual_size_bytes+1 WHERE id=?",asset);
            case "STORAGE" -> jdbc.update("UPDATE media_asset SET ready_storage_id=? WHERE id=?","0".repeat(64),asset);
            case "SOURCE_KEY" -> jdbc.update("UPDATE media_asset SET ready_object_key=storage_key WHERE id=?",asset);
            case "OTHER_ASSET_KEY" -> jdbc.update("UPDATE media_asset SET ready_object_key=? WHERE id=?","private/"+owner.getId()+"/different_asset/ready/"+id(),asset);
            case "BAD_TYPE" -> jdbc.update("UPDATE media_asset SET actual_content_type='text/html' WHERE id=?",asset);
            default -> jdbc.update("UPDATE media_asset SET actual_content_type='video/mp4' WHERE id=?",asset);
        }
        assertProblem(access(content(workspace),revision(workspace),asset,token),503,"DEPENDENCY_UNAVAILABLE"); assertEquals(0,auditCount(owner)); verifyNoInteractions(readSigner);
    }

    @Test void anOldUploadTargetCannotOverwriteTheReadResultObject() throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","WAITING_UPLOAD");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset));
        String source = "private/"+owner.getId()+"/"+asset+"/source"; objectKeys.add(source);
        var upload = new MediaUpload(); upload.setId(id()); upload.setStorageKey(source); upload.setContentType("image/png");
        upload.setSignerId(uploadSigner.identity()); upload.setSignatureAt(LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS),ZoneOffset.UTC)); upload.setSignatureTtlSeconds(600);
        var target = uploadSigner.sign(upload,PNG.length,null);
        assertEquals(200,put(target.url(),PNG).statusCode()); ready(owner,asset,PNG,"image/png");
        var response = access(content(workspace),revision(workspace),asset,token); assertEquals(200,response.statusCode());
        byte[] changed = new byte[PNG.length]; assertEquals(200,put(target.url(),changed).statusCode());
        String url = JsonPath.read(response.body(),"$.data.url");
        var result = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,result.statusCode()); assertArrayEquals(PNG,result.body());
    }

    @Test void failingToPersistTheAuditRollsBackAndDoesNotReturnAnAccessUrl() throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset)); ready(owner,asset,PNG,"image/png");
        doThrow(new TransientDataAccessResourceException("sensitive audit detail")).when(references)
                .insertAudit(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),any());
        var response = access(content(workspace),revision(workspace),asset,token); assertProblem(response,503,"DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive audit detail")); assertEquals(0,auditCount(owner));
    }

    @Test void redisFailureStopsBeforeReferenceAndStorageLookups() throws Exception {
        UserAccount owner = fixture(); String token = token(owner); clearInvocations(references);
        doThrow(new RedisConnectionFailureException("sensitive redis detail")).when(redis).execute(any(RedisScript.class),
                argThat((List<String> keys) -> !keys.isEmpty() && keys.get(0).startsWith(PREFIX+"{content-media-preview}:")),any(),any());
        assertProblem(access("content","revision","asset",token),503,"DEPENDENCY_UNAVAILABLE");
        verifyNoInteractions(references,objects,readSigner);
    }

    @ParameterizedTest @ValueSource(strings = {"MODERATOR","ADMIN"})
    void elevatedRolesCannotPreviewAnotherAuthorsRevision(String role) throws Exception {
        UserAccount owner = fixture(),viewer = fixture(); String asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token(owner),Map.of("kind","ARTICLE","coverAssetId",asset)); ready(owner,asset,PNG,"image/png");
        jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",viewer.getId(),role);
        assertProblem(access(content(workspace),revision(workspace),asset,token(viewer)),404,"RESOURCE_NOT_FOUND");
        verifyNoInteractions(objects,readSigner); assertEquals(0,auditCount(viewer));
    }

    @ParameterizedTest @ValueSource(strings = {"CONTENT","REVISION","REFERENCE"})
    void databaseLookupFailuresAre503WithoutIssuingUrls(String stage) throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset)); ready(owner,asset,PNG,"image/png");
        var failure = new TransientDataAccessResourceException("sensitive lookup detail");
        switch(stage) {
            case "CONTENT" -> doThrow(failure).when(references).findOwned(anyString(),anyString());
            case "REVISION" -> doThrow(failure).when(references).findRevision(anyString(),anyString());
            default -> doThrow(failure).when(references).findUsages(anyString(),anyString());
        }
        var response = access(content(workspace),revision(workspace),asset,token); assertProblem(response,503,"DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("sensitive lookup detail")); assertEquals(0,auditCount(owner)); verifyNoInteractions(objects,readSigner);
    }

    @Test void aFinalDatabaseFailureAfterHeadStillPreventsSigning() throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset)); ready(owner,asset,PNG,"image/png");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            doThrow(new TransientDataAccessResourceException("final data unavailable")).when(references).findOwned(anyString(),anyString());
            return null;
        }).when(objects).verify(any());
        assertProblem(access(content(workspace),revision(workspace),asset,token),503,"DEPENDENCY_UNAVAILABLE");
        assertEquals(0,auditCount(owner)); verifyNoInteractions(readSigner);
    }

    @Test void successfulRepeatedRequestsAreCountedAndEachGrantHasItsOwnAudit() throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset)); ready(owner,asset,PNG,"image/png");
        for (int i = 0; i < 3; i++) assertEquals(200,access(content(workspace),revision(workspace),asset,token).statusCode());
        assertProblem(access(content(workspace),revision(workspace),asset,token),429,"RATE_LIMITED"); assertEquals(3,auditCount(owner));
    }

    @Test void referencedReadyVideoUsesItsActualTypeAndSupportsRangeReads() throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_VIDEO","PROCESSING");
        var workspace = create(token,Map.of("kind","VIDEO","videoAssetId",asset));
        byte[] sample = HexFormat.of().parseHex("00000018667479706d703432000000006d70343269736f6d000000086d646174");
        ready(owner,asset,sample,"video/mp4"); var response = access(content(workspace),revision(workspace),asset,token);
        assertEquals(200,response.statusCode()); assertEquals("video/mp4",JsonPath.read(response.body(),"$.data.contentType"));
        String url = JsonPath.read(response.body(),"$.data.url");
        var part = client.send(HttpRequest.newBuilder(URI.create(url)).header("Range","bytes=0-3").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206,part.statusCode()); assertArrayEquals(Arrays.copyOfRange(sample,0,4),part.body());
    }

    @Test void swaggerMatchesTheThreePathIdsAndFourFieldMediaAccessContract() throws Exception {
        var response = get("/v3/api-docs",null); assertEquals(200,response.statusCode());
        Map<String,Object> operation = JsonPath.read(response.body(),"$.paths['/api/v1/creator/contents/{contentId}/revisions/{revisionId}/media/{assetId}/access'].get");
        assertEquals("getDraftMediaAccess",operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        var parameters = (List<Map<String,Object>>)operation.get("parameters"); assertEquals(3,parameters.size());
        var names = new HashSet<String>();
        for (var parameter : parameters) {
            names.add((String)parameter.get("name")); assertEquals("path",parameter.get("in")); assertEquals(true,parameter.get("required"));
            var schema = (Map<?,?>)parameter.get("schema"); assertEquals("string",schema.get("type")); assertEquals(1,schema.get("minLength")); assertEquals(64,schema.get("maxLength"));
        }
        assertEquals(Set.of("contentId","revisionId","assetId"),names);
        Map<String,Object> schema = JsonPath.read(response.body(),"$.components.schemas.MediaAccess");
        assertEquals(Set.of("assetId","url","expiresAt","contentType"),new HashSet<>((List<String>)schema.get("required")));
        assertEquals(Set.of("assetId","url","expiresAt","contentType"),((Map<?,?>)schema.get("properties")).keySet());
        assertEquals("uri",JsonPath.read(response.body(),"$.components.schemas.MediaAccess.properties.url.format"));
    }

    private HttpResponse<Void> put(String url,byte[] bytes) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding());
    }
    private String content(Map<String,Object> workspace) { return (String)workspace.get("id"); }
    private String revision(Map<String,Object> workspace) { return (String)((Map<?,?>)workspace.get("workingRevision")).get("id"); }
    private int auditCount(UserAccount owner) { return jdbc.queryForObject("SELECT COUNT(*) FROM content_media_preview_audit WHERE actor_id=?",Integer.class,owner.getId()); }
    private void changeAccount(UserAccount owner,String change) {
        switch(change) {
            case "AUTH_VERSION" -> jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?",owner.getId());
            case "UNVERIFIED" -> jdbc.update("UPDATE app_user SET email_verified=0 WHERE id=?",owner.getId());
            case "NO_USER" -> jdbc.update("DELETE FROM app_user_role WHERE user_id=?",owner.getId());
            default -> jdbc.update("UPDATE app_user SET account_status=? WHERE id=?",change,owner.getId());
        }
    }
    private String permissionError(String change) { return change.equals("AUTH_VERSION") ? "AUTHENTICATION_REQUIRED" : change.equals("DISABLED") ? "ACCOUNT_RESTRICTED" : "PERMISSION_DENIED"; }

    @ParameterizedTest @ValueSource(strings = {"USER","SOURCE"})
    void repeatedUnreadyAndNotFoundRequestsConsumeIndependentPreviewQuota(String scope) throws Exception {
        UserAccount owner = fixture(); String token = token(owner),asset = asset(owner,"CONTENT_IMAGE","PROCESSING");
        var workspace = create(token,Map.of("kind","ARTICLE","coverAssetId",asset));
        String content = (String)workspace.get("id"),revision = (String)((Map<?,?>)workspace.get("workingRevision")).get("id");
        if (scope.equals("USER")) {
            assertProblem(access(content,revision,asset,token),409,"MEDIA_NOT_READY");
            assertProblem(access(content,revision,"missing",token),404,"RESOURCE_NOT_FOUND");
            assertProblem(access(content,revision,asset,token),409,"MEDIA_NOT_READY");
        } else redis.opsForValue().set(PREFIX+"{content-media-preview}:source:"+com.freecontent.community.auth.security.TokenDigest.sha256("127.0.0.1"),"120",Duration.ofMinutes(1));
        var limited = access(content,revision,asset,token); assertProblem(limited,429,"RATE_LIMITED");
        assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) >= 1);
        assertTrue(redis.keys(PREFIX+"{content-query}:*").isEmpty());
    }

    private UserAccount fixture() {
        var user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("media-preview-"+user.getId()+"@example.test"); user.setEmailNormalized(user.getEmail());
        owners.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(),user.getCreatedAt()); return user;
    }
    private String token(UserAccount user) { return sessions.create(user.getId(),user.getAuthVersion(),"media-preview-test").accessToken(); }
    private String asset(UserAccount owner,String scene,String state) {
        String asset = id(),type = scene.equals("CONTENT_VIDEO") ? "video/mp4" : "image/png";
        jdbc.update("INSERT INTO media_asset(id,owner_id,scene,file_name,declared_content_type,declared_size_bytes,storage_key,state,actual_content_type,actual_size_bytes,created_at) VALUES(?,?,?,?,?,68,?,?,?,68,UTC_TIMESTAMP(6))",
                asset,owner.getId(),scene,"fixture",type,"private/"+owner.getId()+"/"+asset+"/source",state,type); return asset;
    }
    private Map<String,Object> create(String token,Map<String,Object> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/creator/contents"))
                .header("Authorization","Bearer "+token).header("Idempotency-Key",id()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = client.send(request,HttpResponse.BodyHandlers.ofString()); assertEquals(201,response.statusCode());
        return JsonPath.read(response.body(),"$.data");
    }
    private HttpResponse<String> access(String content,String revision,String asset,String token) throws Exception {
        return get("/api/v1/creator/contents/"+content+"/revisions/"+revision+"/media/"+asset+"/access",token);
    }
    private HttpResponse<String> get(String path,String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization","Bearer "+token);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void assertProblem(HttpResponse<String> response,int status,String code) {
        assertEquals(status,response.statusCode()); assertEquals(code,JsonPath.read(response.body(),"$.code"));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }
    private String id() { return UUID.randomUUID().toString().replace("-",""); }
}
