package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.config.ProfileUpdateProperties;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.freecontent.community.user.ratelimit.ProfileUpdateRateLimiter;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.email-challenge.delivery-enabled=false")
class ProfileUpdateFlowIT {
    private static final String PREFIX = "test:profile-update-it:" + UUID.randomUUID() + ":";
    private static final String PASSWORD = " ProfileUpdatePassword! ";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private PasswordHasher passwords;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisSessionStore sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoSpyBean private SessionAuthenticationService authentication;
    private final List<String> fixtureIds = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void prefixes(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.profile-update.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : fixtureIds) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void dynamicNicknameAndBioUpdatesKeepOmittedFieldsAndSessionsUnchanged() throws Exception {
        UserAccount account = fixture();
        UserAccount other = fixture();
        String token = login(account);
        var beforeSession = sessions.find(token);
        HttpResponse<String> first = patch(token, body("expectedVersion", 1, "displayName", "新昵称"));
        assertEquals(200, first.statusCode());
        assertEquals("新昵称", JsonPath.read(first.body(), "$.data.displayName"));
        assertEquals("原简介", JsonPath.read(first.body(), "$.data.bio"));
        assertEquals("avatar_before", JsonPath.read(first.body(), "$.data.avatarAssetId"));
        assertEquals(2, (Integer) JsonPath.read(first.body(), "$.data.version"));
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(first.headers().firstValue("Set-Cookie").isEmpty());
        HttpResponse<String> second = patch(token, body("expectedVersion", 2, "bio", ""));
        assertEquals(200, second.statusCode());
        UserAccount after = users.selectById(account.getId());
        assertEquals("新昵称", after.getDisplayName());
        assertEquals("", after.getBio());
        assertEquals("avatar_before", after.getAvatarAssetId());
        assertEquals(3, after.getVersion());
        assertEquals(1L, after.getAuthVersion());
        assertEquals(account.getPasswordHash(), after.getPasswordHash());
        assertEquals(other.getDisplayName(), users.selectById(other.getId()).getDisplayName());
        assertEquals(1, users.selectById(other.getId()).getVersion());
        assertEquals(beforeSession.expiresAt(), sessions.find(token).expiresAt());
        HttpResponse<String> reread = request("GET", "/api/v1/me", token, null);
        assertEquals(200, reread.statusCode());
        assertEquals("新昵称", JsonPath.read(reread.body(), "$.data.displayName"));
        assertFalse(first.body().contains("passwordHash"));
        assertFalse(first.body().contains("tokenDigest"));
        assertFalse(first.body().contains("authVersion"));
    }

    @Test
    void explicitNullClearsAvatarWhileOmittedAvatarRemainsUnchanged() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        HttpResponse<String> response = patch(token, body("expectedVersion", 1, "avatarAssetId", null));
        assertEquals(200, response.statusCode());
        Map<String, Object> data = JsonPath.read(response.body(), "$.data");
        assertTrue(data.containsKey("avatarAssetId"));
        assertNull(data.get("avatarAssetId"));
        assertNull(users.selectById(account.getId()).getAvatarAssetId());
        assertEquals("原昵称", users.selectById(account.getId()).getDisplayName());
        assertEquals("原简介", users.selectById(account.getId()).getBio());
        assertEquals(2, users.selectById(account.getId()).getVersion());
    }

    @Test
    void sameValuesReturn200WithoutChangingVersionOrUpdatedTime() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        var before = users.selectById(account.getId());
        HttpResponse<String> response = patch(token, body("expectedVersion", 1, "displayName", "原昵称", "bio", "原简介"));
        assertEquals(200, response.statusCode());
        assertEquals(1, (Integer) JsonPath.read(response.body(), "$.data.version"));
        assertEquals(before.getUpdatedAt(), users.selectById(account.getId()).getUpdatedAt());
        assertEquals(1, users.selectById(account.getId()).getVersion());
        assertEquals(1L, users.selectById(account.getId()).getAuthVersion());
    }

    @Test
    void staleVersionReturnsCurrentVersionEvenWhenValuesAlreadyMatch() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        assertEquals(200, patch(token, body("expectedVersion", 1, "displayName", "新昵称")).statusCode());
        HttpResponse<String> stale = patch(token, body("expectedVersion", 1, "displayName", "新昵称"));
        assertEquals(409, stale.statusCode());
        assertEquals("RESOURCE_VERSION_CONFLICT", JsonPath.read(stale.body(), "$.code"));
        assertEquals(2, (Integer) JsonPath.read(stale.body(), "$.currentVersion"));
        assertEquals(2, users.selectById(account.getId()).getVersion());
    }

    @Test
    void unsupportedNonNullAvatarFailsWholeUpdateWithoutChangingOtherFields() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        HttpResponse<String> response = patch(token, body("expectedVersion", 1, "displayName", "不能部分保存", "avatarAssetId", "new_avatar"));
        assertEquals(503, response.statusCode());
        assertEquals("DEPENDENCY_UNAVAILABLE", JsonPath.read(response.body(), "$.code"));
        UserAccount after = users.selectById(account.getId());
        assertEquals(account.getDisplayName(), after.getDisplayName());
        assertEquals(account.getAvatarAssetId(), after.getAvatarAssetId());
        assertEquals(1, after.getVersion());
        assertEquals(account.getUpdatedAt(), after.getUpdatedAt());
    }

    @Test
    void realDatabaseAcceptsMaximumUnicodeLengthsAndBindsSqlValues() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        String name = "😀".repeat(40);
        String bio = "😀".repeat(500);
        assertEquals(200, patch(token, body("expectedVersion", 1, "displayName", name, "bio", bio)).statusCode());
        assertEquals(name, users.selectById(account.getId()).getDisplayName());
        assertEquals(bio, users.selectById(account.getId()).getBio());
        String sqlText = "x', account_status='DISABLED";
        assertEquals(200, patch(token, body("expectedVersion", 2, "displayName", sqlText)).statusCode());
        assertEquals(sqlText, users.selectById(account.getId()).getDisplayName());
        assertEquals(UserAccount.Status.ACTIVE, users.selectById(account.getId()).getAccountStatus());
    }

    @Test
    void accountRestrictionChangedAfterAuthenticationStillBlocksWriting() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        doAnswer(invocation -> {
            SessionPrincipal actor = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", account.getId());
            return actor;
        }).when(authentication).authenticate(token);
        assertEquals(403, patch(token, body("expectedVersion", 1, "bio", "不应写入")).statusCode());
        assertEquals("原简介", users.selectById(account.getId()).getBio());
        assertEquals(1, users.selectById(account.getId()).getVersion());
    }

    @Test
    void changedCredentialVersionAfterAuthenticationCannotUpdateProfile() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        doAnswer(invocation -> {
            SessionPrincipal actor = (SessionPrincipal) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", account.getId());
            return actor;
        }).when(authentication).authenticate(token);
        HttpResponse<String> response = patch(token, body("expectedVersion", 1, "displayName", "不应写入"));
        assertEquals(401, response.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", JsonPath.read(response.body(), "$.code"));
        assertEquals("原昵称", users.selectById(account.getId()).getDisplayName());
        assertEquals(1, users.selectById(account.getId()).getVersion());
        assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
    }

    @Test
    void concurrentEditsWithOneVersionCannotOverwriteEachOther() throws Exception {
        UserAccount account = fixture();
        String token = login(account);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return patch(token, body("expectedVersion", 1, "displayName", "并发昵称")).statusCode();
            });
            Future<Integer> second = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return patch(token, body("expectedVersion", 1, "bio", "并发简介")).statusCode();
            });
            start.countDown();
            List<Integer> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(results, 200));
            assertEquals(1, Collections.frequency(results, 409));
            UserAccount after = users.selectById(account.getId());
            assertEquals(2, after.getVersion());
            assertTrue((after.getDisplayName().equals("并发昵称") && after.getBio().equals("原简介"))
                    || (after.getDisplayName().equals("原昵称") && after.getBio().equals("并发简介")));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void profileQuotaIsIndependentAndReturnsRetryAfter() {
        String prefix = PREFIX + "quota:";
        ProfileUpdateRateLimiter limiter = new ProfileUpdateRateLimiter(redis, new ProfileUpdateProperties(30, 20, prefix));
        for (int i = 0; i < 20; i++) limiter.acquire("same-user", "user-source");
        BusinessException user = assertThrows(BusinessException.class, () -> limiter.acquire("same-user", "other-source"));
        assertEquals(ErrorCode.RATE_LIMITED, user.errorCode());
        assertTrue(user.retryAfterSeconds() > 0 && user.retryAfterSeconds() <= 3600);
        for (int i = 0; i < 30; i++) limiter.acquire("user" + i, "source-limit");
        BusinessException source = assertThrows(BusinessException.class, () -> limiter.acquire("new-user", "source-limit"));
        assertEquals(ErrorCode.RATE_LIMITED, source.errorCode());
        assertTrue(source.retryAfterSeconds() > 0 && source.retryAfterSeconds() <= 60);
        assertFalse(redis.hasKey(prefix + "{profile-update}:user:" + TokenDigest.sha256("new-user")));
    }

    @Test
    void swaggerDeclaresOnlyFourInputPropertiesAndNullableAvatar() throws Exception {
        HttpResponse<String> docs = request("GET", "/v3/api-docs", null, null);
        assertEquals(200, docs.statusCode());
        Map<String, Object> operation = JsonPath.read(docs.body(), "$.paths['/api/v1/me'].patch");
        assertEquals("updateCurrentUser", operation.get("operationId"));
        assertTrue(((List<?>) operation.get("security")).stream().anyMatch(value -> ((Map<?, ?>) value).containsKey("bearerAuth")));
        Map<String, Object> schema = JsonPath.read(docs.body(), "$.components.schemas.UpdateProfileRequest");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(Set.of("expectedVersion", "displayName", "bio", "avatarAssetId"), properties.keySet());
        assertEquals(List.of("expectedVersion"), schema.get("required"));
        assertEquals(2, schema.get("minProperties"));
        assertEquals(false, schema.get("additionalProperties"));
        assertTrue(properties.get("avatarAssetId").toString().contains("null"));
    }

    private UserAccount fixture() {
        UserAccount account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("profile-update-it-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        account.setDisplayName("原昵称");
        account.setBio("原简介");
        account.setAvatarAssetId("avatar_before");
        account.setPasswordHash(passwords.encode(PASSWORD));
        fixtureIds.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private String login(UserAccount account) throws Exception {
        HttpResponse<String> response = request("POST", "/api/v1/auth/sessions", null,
                body("email", account.getEmail(), "password", PASSWORD));
        assertEquals(201, response.statusCode());
        return JsonPath.read(response.body(), "$.data.accessToken");
    }

    private HttpResponse<String> patch(String token, Map<String, Object> body) throws Exception {
        return request("PATCH", "/api/v1/me", token, body);
    }

    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> body(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
