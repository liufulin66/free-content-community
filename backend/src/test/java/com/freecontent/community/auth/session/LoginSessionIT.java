package com.freecontent.community.auth.session;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.LoginProperties;
import com.freecontent.community.auth.ratelimit.LoginRateLimiter;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP、MySQL、Redis；只清理本类生成的唯一账号和Redis前缀。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth.email-challenge.delivery-enabled=false", "app.auth.login.ip-per-minute-limit=200"})
@Import(LoginSessionIT.ProbeController.class)
class LoginSessionIT {
    private static final String PREFIX = "test:login-it:" + UUID.randomUUID() + ":";
    private static final String PASSWORD = " IntegrationPassword! ";
    @Value("${local.server.port}") private int port;
    @Autowired private RedisSessionStore sessions;
    @Autowired private LoginProperties properties;
    @Autowired private StringRedisTemplate redis;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private PasswordHasher passwords;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> fixtureIds = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedPrefix(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanFixtures() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : fixtureIds) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            jdbc.update("DELETE FROM app_user WHERE id=?", id);
        }
    }

    @Test
    void realLoginAndBearerUseCurrentAccountStateWithoutCookies() throws Exception {
        UserAccount user = fixture();
        HttpResponse<String> wrong = login(user.getEmail(), "wrong");
        HttpResponse<String> unknown = login("unknown-" + UUID.randomUUID() + "@example.test", "wrong");
        assertEquals(401, wrong.statusCode());
        assertEquals(401, unknown.statusCode());
        assertEquals("AUTHENTICATION_FAILED", JsonPath.read(wrong.body(), "$.code"));
        assertEquals(JsonPath.<String>read(wrong.body(), "$.code"), JsonPath.<String>read(unknown.body(), "$.code"));

        HttpResponse<String> response = login(" " + user.getEmail().toUpperCase(Locale.ROOT) + " ", PASSWORD);
        assertEquals(201, response.statusCode());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        String token = JsonPath.read(response.body(), "$.data.accessToken");
        assertEquals(43, token.length());
        assertEquals("Bearer", JsonPath.read(response.body(), "$.data.tokenType"));
        StoredSession stored = sessions.find(token);
        assertNotNull(stored);
        assertEquals(86400, Duration.between(stored.createdAt(), stored.expiresAt()).toSeconds());
        assertEquals(stored.expiresAt(), Instant.parse(JsonPath.read(response.body(), "$.data.expiresAt")));
        assertFalse(response.body().contains("passwordHash"));
        assertFalse(response.body().contains("authVersion"));
        String tokenKey = PREFIX + "{sessions}:token:" + TokenDigest.sha256(token);
        assertFalse(tokenKey.contains(token));
        assertFalse(redis.opsForHash().entries(tokenKey).containsKey("accessToken"));
        assertEquals(200, request("GET", "/__login_it/me", token).statusCode());
        assertEquals(401, request("GET", "/__login_it/me", null).statusCode());

        jdbc.update("UPDATE app_user SET display_name=?, version=version+1 WHERE id=?", "新昵称", user.getId());
        assertEquals("新昵称", JsonPath.read(request("GET", "/__login_it/me", token).body(), "$.displayName"));
        jdbc.update("INSERT INTO app_user_role (user_id,role_code,created_at) VALUES (?,'ADMIN',UTC_TIMESTAMP(6))", user.getId());
        List<String> currentRoles = JsonPath.read(request("GET", "/__login_it/me", token).body(), "$.roles");
        assertTrue(currentRoles.contains("ADMIN"));
        jdbc.update("DELETE FROM app_user_role WHERE user_id=? AND role_code='ADMIN'", user.getId());
        currentRoles = JsonPath.read(request("GET", "/__login_it/me", token).body(), "$.roles");
        assertFalse(currentRoles.contains("ADMIN"));

        jdbc.update("UPDATE app_user SET account_status='WRITE_BLOCKED' WHERE id=?", user.getId());
        assertFalse((Boolean) JsonPath.read(request("GET", "/__login_it/me", token).body(), "$.canCreate"));
        assertEquals(403, request("POST", "/__login_it/write", token).statusCode());
        assertEquals(201, login(user.getEmail(), PASSWORD).statusCode());
        jdbc.update("UPDATE app_user SET account_status='DISABLED' WHERE id=?", user.getId());
        assertEquals(403, login(user.getEmail(), PASSWORD).statusCode());
        assertEquals(403, request("GET", "/__login_it/me", token).statusCode());
        jdbc.update("UPDATE app_user SET account_status='ACTIVE', auth_version=auth_version+1 WHERE id=?", user.getId());
        assertEquals(401, request("GET", "/__login_it/me", token).statusCode());
        assertEquals(201, login(user.getEmail(), PASSWORD).statusCode());
    }

    @Test
    void sixthSessionEvictsOldestAndEveryNewLoginHasItsOwnDeadline() {
        UserAccount user = fixture();
        Instant base = Instant.ofEpochMilli(System.currentTimeMillis());
        List<IssuedSession> issued = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            RedisSessionStore clocked = new RedisSessionStore(redis, properties,
                    Clock.fixed(base.plusSeconds(i), ZoneOffset.UTC));
            issued.add(clocked.create(user.getId(), 1, "device" + i));
        }
        assertNull(sessions.find(issued.get(0).accessToken()));
        for (int i = 1; i < 6; i++) assertNotNull(sessions.find(issued.get(i).accessToken()));
        assertEquals(5, redis.opsForZSet().zCard(PREFIX + "{sessions}:user:" + user.getId()));
        assertEquals(base.plusSeconds(86405), issued.get(5).session().expiresAt());
        assertEquals(issued.get(1).session().expiresAt(), sessions.find(issued.get(1).accessToken()).expiresAt());
    }

    @Test
    void concurrentCreationNeverLeavesMoreThanFiveValidSessions() throws Exception {
        UserAccount user = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IssuedSession>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) pending.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return sessions.create(user.getId(), 1, "concurrent");
            }));
            start.countDown();
            List<IssuedSession> results = new ArrayList<>();
            for (Future<IssuedSession> future : pending) results.add(future.get(10, TimeUnit.SECONDS));
            long valid = results.stream().filter(result -> sessions.find(result.accessToken()) != null).count();
            assertEquals(5, valid);
            assertEquals(12, results.stream().map(IssuedSession::accessToken).distinct().count());
            assertEquals(12, results.stream().map(result -> result.session().sessionId()).distinct().count());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void olderAuthVersionCannotDestroyNewerSessions() {
        UserAccount user = fixture();
        IssuedSession old = sessions.create(user.getId(), 1, "old");
        IssuedSession fresh = sessions.create(user.getId(), 2, "new");
        assertNull(sessions.find(old.accessToken()));
        BusinessException ex = assertThrows(BusinessException.class, () -> sessions.create(user.getId(), 1, "stale"));
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, ex.errorCode());
        assertNotNull(sessions.find(fresh.accessToken()));
    }

    @Test
    void lostUserIndexInvalidatesOrphanedSessionsInsteadOfBypassingSessionLimit() {
        UserAccount user = fixture();
        IssuedSession orphan = sessions.create(user.getId(), 1, "orphan");
        redis.delete(PREFIX + "{sessions}:user:" + user.getId());
        IssuedSession fresh = sessions.create(user.getId(), 1, "new");
        assertNull(sessions.find(orphan.accessToken()));
        assertNotNull(sessions.find(fresh.accessToken()));
    }

    @Test
    void readsNeverRenewExpiryAndExpiredSessionCannotBeResurrected() {
        UserAccount user = fixture();
        Instant base = Instant.ofEpochMilli(System.currentTimeMillis());
        var atCreation = new RedisSessionStore(redis, properties, Clock.fixed(base, ZoneOffset.UTC));
        IssuedSession issued = atCreation.create(user.getId(), 1, null);
        String key = PREFIX + "{sessions}:token:" + TokenDigest.sha256(issued.accessToken());
        Long before = redis.getExpire(key, TimeUnit.MILLISECONDS);
        var later = new RedisSessionStore(redis, properties, Clock.fixed(base.plusSeconds(1000), ZoneOffset.UTC));
        assertEquals(issued.session().expiresAt(), later.find(issued.accessToken()).expiresAt());
        assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) <= before);
        assertEquals(Long.toString(base.plusSeconds(1000).toEpochMilli()), redis.opsForHash().get(key, "lastSeenAt"));
        var expired = new RedisSessionStore(redis, properties, Clock.fixed(issued.session().expiresAt(), ZoneOffset.UTC));
        assertNull(expired.find(issued.accessToken()));
        assertFalse(redis.hasKey(key));
        assertNull(later.find(issued.accessToken()));
        assertFalse(redis.hasKey(key));
    }

    @Test
    void realRedisEnforcesBothLoginQuotasAndReturnsRetryAfter() {
        String limitPrefix = PREFIX + "limits:";
        LoginRateLimiter limiter = new LoginRateLimiter(redis, LoginTestSupport.properties(limitPrefix));
        for (int i = 0; i < 10; i++) limiter.acquire("a@example.test", "email-test-ip");
        BusinessException emailError = assertThrows(BusinessException.class,
                () -> limiter.acquire("a@example.test", "other-ip"));
        assertEquals(ErrorCode.RATE_LIMITED, emailError.errorCode());
        assertTrue(emailError.retryAfterSeconds() > 0 && emailError.retryAfterSeconds() <= 900);
        for (int i = 0; i < 30; i++) limiter.acquire("source" + i + "@example.test", "source-test-ip");
        BusinessException sourceError = assertThrows(BusinessException.class,
                () -> limiter.acquire("new@example.test", "source-test-ip"));
        assertEquals(ErrorCode.RATE_LIMITED, sourceError.errorCode());
        assertTrue(sourceError.retryAfterSeconds() > 0 && sourceError.retryAfterSeconds() <= 60);
        assertFalse(redis.hasKey(limitPrefix + "{login}:email:" + TokenDigest.sha256("new@example.test")));
    }

    private UserAccount fixture() {
        UserAccount user = LoginTestSupport.account();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail("login-it-" + user.getId() + "@example.test");
        user.setEmailNormalized(user.getEmail());
        user.setPasswordHash(passwords.encode(PASSWORD));
        fixtureIds.add(user.getId());
        users.insert(user);
        roles.insertDefaultRole(user.getId(), user.getCreatedAt());
        return user;
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/sessions"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/__login_it/me")
        CurrentUser me(@AuthenticationPrincipal SessionPrincipal principal) { return principal.user(); }
        @PostMapping("/__login_it/write")
        String write() { return "ok"; }
    }
}
