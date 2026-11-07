package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.media.entity.MediaAsset;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.media.storage.UploadObjectInspector;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false",
        "app.media.asset-query.user-per-minute-limit=3"})
class MediaAssetQueryFlowIT {
    private static final String PREFIX = "test:media-asset-query:" + UUID.randomUUID() + ":";
    private static final LocalDateTime CREATED = LocalDateTime.ofInstant(Instant.parse("2026-11-07T00:00:00.123456Z"), ZoneOffset.UTC);
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @MockitoSpyBean private MediaAssetMapper assets;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private UploadObjectInspector inspector;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> owners = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedRedis(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.asset-query.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : owners) {
            jdbc.update("DELETE FROM media_asset WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner);
            users.deleteById(owner);
        }
    }

    @Test
    void waitingUploadReturnsTheDeclaredMimeAndUnknownActualSizeWithoutExposingStorageDetails() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> before = assetRow(fixture.assetId());

        var response = get(fixture.assetId(), fixture.token());

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "ownerId", "scene", "state", "contentType", "sizeBytes", "durationSeconds", "width", "height", "createdAt"), data.keySet());
        assertEquals(fixture.assetId(), data.get("id")); assertEquals(fixture.owner(), data.get("ownerId"));
        assertEquals("CONTENT_IMAGE", data.get("scene")); assertEquals("WAITING_UPLOAD", data.get("state"));
        assertEquals("image/png", data.get("contentType")); assertEquals(0L, ((Number) data.get("sizeBytes")).longValue());
        for (String field : List.of("durationSeconds", "width", "height")) assertNull(data.get(field));
        assertEquals(CREATED.toInstant(ZoneOffset.UTC), Instant.parse((String) data.get("createdAt")));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(JsonPath.<String>read(response.body(), "$.requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals(before, assetRow(fixture.assetId()));
        verifyNoInteractions(inspector);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_UPLOAD", "PROCESSING", "READY", "FAILED"})
    void returnsPersistedActualMetadataInEveryStateWithoutPromotingTheState(String state) throws Exception {
        Fixture fixture = fixture();
        jdbc.update("UPDATE media_asset SET state=?,actual_content_type='image/webp',actual_size_bytes=512,width=320,height=200 WHERE id=?", state, fixture.assetId());
        Map<String, Object> before = assetRow(fixture.assetId());
        var response = get(fixture.assetId(), fixture.token());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(state, JsonPath.read(response.body(), "$.data.state"));
        assertEquals("image/webp", JsonPath.read(response.body(), "$.data.contentType"));
        assertEquals(512L, ((Number) JsonPath.read(response.body(), "$.data.sizeBytes")).longValue());
        assertEquals(320, ((Number) JsonPath.read(response.body(), "$.data.width")).intValue());
        assertEquals(200, ((Number) JsonPath.read(response.body(), "$.data.height")).intValue());
        assertNull(JsonPath.read(response.body(), "$.data.durationSeconds"));
        assertEquals(before, assetRow(fixture.assetId())); verifyNoInteractions(inspector);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_UPLOAD", "PROCESSING", "FAILED"})
    void unvalidatedStatesRemainQueryableWithUnknownActualMetadata(String state) throws Exception {
        Fixture fixture = fixture(); jdbc.update("UPDATE media_asset SET state=? WHERE id=?", state, fixture.assetId());
        var response = get(fixture.assetId(), fixture.token());
        assertEquals(200, response.statusCode(), response.body()); assertEquals(state, JsonPath.read(response.body(), "$.data.state"));
        assertEquals("image/png", JsonPath.read(response.body(), "$.data.contentType"));
        assertEquals(0L, ((Number) JsonPath.read(response.body(), "$.data.sizeBytes")).longValue());
        assertNull(JsonPath.read(response.body(), "$.data.width")); assertNull(JsonPath.read(response.body(), "$.data.height"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"actual_content_type=NULL", "actual_content_type=''", "actual_size_bytes=NULL"})
    void readyWithMissingActualMimeOrSizeReturns503InsteadOfFabricatingValidationResults(String missing) throws Exception {
        Fixture fixture = fixture();
        jdbc.update("UPDATE media_asset SET state='READY',actual_content_type='image/png',actual_size_bytes=512 WHERE id=?", fixture.assetId());
        jdbc.update("UPDATE media_asset SET " + missing + " WHERE id=?", fixture.assetId());
        Map<String, Object> before = assetRow(fixture.assetId());
        assertProblem(get(fixture.assetId(), fixture.token()), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(before, assetRow(fixture.assetId()));
    }

    @Test
    void readsCurrentMetadataOnEveryRequest() throws Exception {
        Fixture fixture = fixture();
        var first = get(fixture.assetId(), fixture.token()); assertEquals(200, first.statusCode());
        assertEquals("WAITING_UPLOAD", JsonPath.read(first.body(), "$.data.state"));
        jdbc.update("UPDATE media_asset SET state='READY',actual_content_type='image/png',actual_size_bytes=1024,width=1,height=1 WHERE id=?", fixture.assetId());
        var current = get(fixture.assetId(), fixture.token()); assertEquals(200, current.statusCode());
        assertEquals("READY", JsonPath.read(current.body(), "$.data.state"));
        assertEquals(1024L, ((Number) JsonPath.read(current.body(), "$.data.sizeBytes")).longValue());
    }

    @Test
    void readsPersistedVideoDurationAndLargeSizesWithoutAddingVideoUploadOrProcessing() throws Exception {
        Fixture fixture = fixture();
        jdbc.update("""
                UPDATE media_asset SET scene='CONTENT_VIDEO',state='READY',declared_content_type='video/mp4',
                    declared_size_bytes=4294967296,actual_content_type='video/mp4',actual_size_bytes=4294967296,
                    duration_seconds=12.345678,width=1920,height=1080 WHERE id=?
                """, fixture.assetId());
        var response = get(fixture.assetId(), fixture.token()); assertEquals(200, response.statusCode(), response.body());
        assertEquals(4294967296L, ((Number) JsonPath.read(response.body(), "$.data.sizeBytes")).longValue());
        assertEquals(0, new BigDecimal("12.345678").compareTo(new BigDecimal(JsonPath.read(response.body(), "$.data.durationSeconds").toString())));
        verifyNoInteractions(inspector);
    }

    @Test
    void nonVideoDurationIsNullEvenWhenAnInternalDurationValueExists() throws Exception {
        Fixture fixture = fixture();
        jdbc.update("UPDATE media_asset SET actual_content_type='image/png',actual_size_bytes=1024,duration_seconds=2.5 WHERE id=?", fixture.assetId());
        var response = get(fixture.assetId(), fixture.token()); assertEquals(200, response.statusCode());
        assertNull(JsonPath.read(response.body(), "$.data.durationSeconds"));
    }

    @Test
    void ownershipAndCaseSensitiveIdsAreEnforced() throws Exception {
        Fixture own = fixture(), other = fixture();
        assertProblem(get(own.assetId(), other.token()), 404, "RESOURCE_NOT_FOUND");
        assertProblem(get(own.assetId().toUpperCase(Locale.ROOT), own.token()), 404, "RESOURCE_NOT_FOUND");
        assertProblem(get("missing_asset", own.token()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, get(own.assetId(), own.token()).statusCode());
    }

    @Test
    void anonymousAndMalformedCredentialsReturnTheBearerChallenge() throws Exception {
        Fixture fixture = fixture();
        for (String token : Arrays.asList(null, "bad-token")) {
            var response = get(fixture.assetId(), token); assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidAssetIds")
    void invalidIdsHaveAPathViolationWithoutQueryingAssetStorage(String assetId) throws Exception {
        Fixture fixture = fixture(); clearInvocations(assets);
        var response = get(assetId, fixture.token()); assertProblem(response, 422, "VALIDATION_ERROR");
        assertEquals("path", JsonPath.read(response.body(), "$.violations[0].location"));
        assertEquals("assetId", JsonPath.read(response.body(), "$.violations[0].path")); verifyNoInteractions(assets);
    }

    static Stream<String> invalidAssetIds() { return Stream.of("bad.asset", "a".repeat(65)); }

    @ParameterizedTest
    @ValueSource(strings = {"auth_version=auth_version+1", "account_status='DISABLED'", "email_verified=0", "account_status='WRITE_BLOCKED'"})
    void rechecksTheAccountAfterBearerAuthentication(String change) throws Exception {
        Fixture fixture = fixture(); Map<String, Object> before = assetRow(fixture.assetId());
        doAnswer(invocation -> {
            SessionPrincipal principal = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET " + change + " WHERE id=?", fixture.owner()); return principal;
        }).when(authentication).authenticate(fixture.token());
        var response = get(fixture.assetId(), fixture.token());
        if (change.contains("WRITE_BLOCKED")) assertEquals(200, response.statusCode(), response.body());
        else if (change.startsWith("auth_version")) assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
        else assertProblem(response, 403, change.startsWith("email_verified") ? "PERMISSION_DENIED" : "ACCOUNT_RESTRICTED");
        assertEquals(before, assetRow(fixture.assetId()));
    }

    @Test
    void repeatedReadsAnd404ResultsConsumeTheAssetQueryQuota() throws Exception {
        Fixture fixture = fixture();
        assertEquals(200, get(fixture.assetId(), fixture.token()).statusCode());
        assertProblem(get("missing_asset", fixture.token()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, get(fixture.assetId(), fixture.token()).statusCode());
        var response = get(fixture.assetId(), fixture.token()); assertProblem(response, 429, "RATE_LIMITED");
        long retry = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()); assertTrue(retry >= 1 && retry <= 60);
    }

    @Test
    void databaseReadFailureReturns503WithoutExposingInternalDetails() throws Exception {
        Fixture fixture = fixture();
        doThrow(new TransientDataAccessResourceException("private database query details"))
                .when(assets).findOwned(fixture.assetId(), fixture.owner());
        var response = get(fixture.assetId(), fixture.token()); assertProblem(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertFalse(response.body().contains("private database query details"));
    }

    @Test
    void openApiDeclaresTheOwnedQueryAndNullableMetadataFields() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/media/assets/{assetId}'].get");
        assertEquals("getMyAsset", operation.get("operationId")); assertFalse(operation.containsKey("requestBody"));
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertEquals(Set.of("200", "400", "401", "403", "404", "422", "429", "500", "503"), responses.keySet());
        for (String code : responses.keySet()) {
            Map<?, ?> headers = (Map<?, ?>) ((Map<?, ?>) responses.get(code)).get("headers");
            assertNotNull(headers); assertTrue(headers.keySet().containsAll(Set.of("X-Request-Id", "Cache-Control")));
            if ("401".equals(code)) assertTrue(headers.containsKey("WWW-Authenticate"));
            if ("429".equals(code)) assertTrue(headers.containsKey("Retry-After"));
        }
        List<String> required = JsonPath.read(response.body(), "$.components.schemas.Asset.required");
        assertEquals(Set.of("id", "ownerId", "scene", "state", "contentType", "sizeBytes", "durationSeconds", "width", "height", "createdAt"), new HashSet<>(required));
        for (String field : List.of("durationSeconds", "width", "height")) {
            Map<String, Object> schema = JsonPath.read(response.body(), "$.components.schemas.Asset.properties." + field);
            assertTrue(schema.get("type") instanceof List<?> types && types.contains("null"), schema.toString());
        }
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, JsonPath.read(response.body(), "$.code"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
    }

    private Fixture fixture() {
        UserAccount user = LoginTestSupport.account(); user.setId(id()); user.setAuthVersion(1L);
        user.setEmail("asset-query-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        owners.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        var asset = new MediaAsset(); asset.setId("asset_" + id()); asset.setOwnerId(user.getId());
        asset.setScene("CONTENT_IMAGE"); asset.setFileName("declared-image.png"); asset.setDeclaredContentType("image/png");
        asset.setDeclaredSizeBytes(1024L); asset.setExpectedSha256("a".repeat(64));
        asset.setStorageKey("private/" + user.getId() + "/" + asset.getId() + "/source");
        asset.setState("WAITING_UPLOAD"); asset.setCreatedAt(CREATED); assets.insert(asset);
        return new Fixture(user.getId(), sessions.create(user.getId(), user.getAuthVersion(), "asset-query-test").accessToken(), asset.getId());
    }

    private HttpResponse<String> get(String assetId, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/media/assets/" + assetId))
                .timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> assetRow(String id) { return jdbc.queryForMap("SELECT * FROM media_asset WHERE id=?", id); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private record Fixture(String owner, String token, String assetId) { }
}
