package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.session.StoredSession;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.email-challenge.delivery-enabled=false")
class CurrentUserFlowIT {
    private static final String PREFIX = "test:current-user-it:" + UUID.randomUUID() + ":";
    private static final String PASSWORD = " CurrentUserPassword! ";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private PasswordHasher passwords;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    private final List<String> fixtureIds = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedPrefix(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
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
    void returnsOwnContractProfileWithoutSecretsOrChangingVersionsOrSessionDeadline() throws Exception {
        UserAccount first = fixture("第一个用户");
        UserAccount second = fixture("第二个用户");
        String firstToken = login(first);
        String secondToken = login(second);
        StoredSession before = sessions.find(firstToken);
        String key = PREFIX + "{sessions}:token:" + TokenDigest.sha256(firstToken);
        long ttlBefore = redis.getExpire(key, TimeUnit.MILLISECONDS);
        HttpResponse<String> response = request("GET", "/api/v1/me", firstToken);
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        Map<String, Object> root = JsonPath.read(response.body(), "$");
        assertEquals(Set.of("data", "requestId", "serverTime"), root.keySet());
        assertEquals(root.get("requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertNotNull(Instant.parse((String) root.get("serverTime")));
        Map<String, Object> profile = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("id", "email", "emailVerified", "displayName", "bio", "avatarAssetId", "roles",
                "accountStatus", "canCreate", "version", "createdAt"), profile.keySet());
        assertEquals(first.getId(), profile.get("id"));
        assertEquals(first.getEmail(), profile.get("email"));
        assertEquals("第一个用户", profile.get("displayName"));
        assertEquals(List.of("USER"), profile.get("roles"));
        assertEquals(true, profile.get("canCreate"));
        assertNull(profile.get("avatarAssetId"));
        assertEquals(second.getId(), JsonPath.read(request("GET", "/api/v1/me", secondToken).body(), "$.data.id"));
        assertEquals(1, users.selectById(first.getId()).getVersion());
        assertEquals(1L, users.selectById(first.getId()).getAuthVersion());
        assertEquals(before.expiresAt(), sessions.find(firstToken).expiresAt());
        assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) <= ttlBefore);

        HttpResponse<String> docs = request("GET", "/v3/api-docs", null);
        assertEquals(200, docs.statusCode());
        Map<String, Object> operation = JsonPath.read(docs.body(), "$.paths['/api/v1/me'].get");
        assertEquals("getCurrentUser", operation.get("operationId"));
        assertTrue(((Map<?, ?>) operation.get("responses")).containsKey("200"));
        assertFalse(operation.containsKey("requestBody"));
        assertTrue(!operation.containsKey("parameters") || ((List<?>) operation.get("parameters")).isEmpty());
        assertTrue(((List<?>) operation.get("security")).stream().anyMatch(value -> ((Map<?, ?>) value).containsKey("bearerAuth")));
    }

    @Test
    void eachRequestReflectsCurrentDatabaseProfileAndRoles() throws Exception {
        UserAccount account = fixture("原昵称");
        String token = login(account);
        assertEquals("原昵称", JsonPath.read(request("GET", "/api/v1/me", token).body(), "$.data.displayName"));
        jdbc.update("UPDATE app_user SET display_name=?, bio=?, version=version+1 WHERE id=?", "新昵称", "当前简介", account.getId());
        jdbc.update("INSERT INTO app_user_role (user_id,role_code,created_at) VALUES (?,'ADMIN',UTC_TIMESTAMP(6))", account.getId());
        HttpResponse<String> changed = request("GET", "/api/v1/me", token);
        assertEquals(200, changed.statusCode());
        assertEquals("新昵称", JsonPath.read(changed.body(), "$.data.displayName"));
        assertEquals("当前简介", JsonPath.read(changed.body(), "$.data.bio"));
        assertEquals(2, (Integer) JsonPath.read(changed.body(), "$.data.version"));
        List<String> currentRoles = JsonPath.read(changed.body(), "$.data.roles");
        assertTrue(currentRoles.contains("ADMIN"));
        jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='ADMIN'", account.getId());
        currentRoles = JsonPath.read(request("GET", "/api/v1/me", token).body(), "$.data.roles");
        assertEquals(List.of("USER"), currentRoles);
    }

    @Test
    void currentAccountStatusAndAuthVersionAreEnforced() throws Exception {
        UserAccount account = fixture("账号状态验证");
        String token = login(account);
        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", account.getId());
        HttpResponse<String> blocked = request("GET", "/api/v1/me", token);
        assertEquals(200, blocked.statusCode());
        assertEquals("WRITE_BLOCKED", JsonPath.read(blocked.body(), "$.data.accountStatus"));
        assertFalse((Boolean) JsonPath.read(blocked.body(), "$.data.canCreate"));
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", account.getId());
        HttpResponse<String> disabled = request("GET", "/api/v1/me", token);
        assertEquals(403, disabled.statusCode());
        assertEquals("ACCOUNT_RESTRICTED", JsonPath.read(disabled.body(), "$.code"));
        jdbc.update("UPDATE app_user SET account_status='ACTIVE', auth_version=auth_version+1 WHERE id=?", account.getId());
        HttpResponse<String> stale = request("GET", "/api/v1/me", token);
        assertEquals(401, stale.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", JsonPath.read(stale.body(), "$.code"));
    }

    @Test
    void missingMalformedExpiredAndRevokedTokensCannotReturnIdentity() throws Exception {
        assertEquals(401, request("GET", "/api/v1/me", null).statusCode());
        assertEquals(401, request("GET", "/api/v1/me", "bad-token").statusCode());
        assertEquals(401, request("GET", "/api/v1/me", "b".repeat(43)).statusCode());
        UserAccount account = fixture("失效会话验证");
        String expired = login(account);
        redis.opsForHash().put(PREFIX + "{sessions}:token:" + TokenDigest.sha256(expired),
                "expiresAt", Long.toString(System.currentTimeMillis() - 1));
        assertEquals(401, request("GET", "/api/v1/me", expired).statusCode());
        String current = login(account);
        assertEquals(200, request("GET", "/api/v1/me", current).statusCode());
        assertEquals(204, request("DELETE", "/api/v1/auth/sessions/current", current).statusCode());
        assertEquals(401, request("GET", "/api/v1/me", current).statusCode());
    }

    private UserAccount fixture(String displayName) {
        UserAccount account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("current-user-it-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        account.setDisplayName(displayName);
        account.setPasswordHash(passwords.encode(PASSWORD));
        fixtureIds.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private String login(UserAccount account) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/sessions"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", account.getEmail(), "password", PASSWORD))));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        return JsonPath.read(response.body(), "$.data.accessToken");
    }

    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
