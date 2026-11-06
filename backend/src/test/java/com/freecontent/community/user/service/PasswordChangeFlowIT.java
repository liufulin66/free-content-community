package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.user.config.PasswordChangeProperties;
import com.freecontent.community.auth.dto.LoginRequest;
import com.freecontent.community.user.ratelimit.PasswordChangeRateLimiter;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.LoginService;
import com.freecontent.community.user.service.transaction.PasswordChangeTransactionService;
import com.freecontent.community.auth.session.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.email-challenge.delivery-enabled=false")
@Import(PasswordChangeFlowIT.ProbeController.class)
class PasswordChangeFlowIT {
    private static final String PREFIX = "test:password-change-it:" + UUID.randomUUID() + ":";
    private static final String OLD = " PreviousPassword! ";
    private static final String NEXT = " NewAccountPassword! ";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JsonMapper json;
    @Autowired private LoginService logins;
    @Autowired private PasswordChangeTransactionService transactions;
    @MockitoSpyBean private PasswordHasher passwords;
    @MockitoSpyBean private RedisSessionStore sessions;
    private final List<String> fixtureIds = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void prefixes(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.user.password-change.redis-key-prefix", () -> PREFIX);
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
    void successfulChangeRevokesAllOldSessionsAndRequiresNewPassword() throws Exception {
        UserAccount account = fixture();
        String current = login(account.getEmail(), OLD);
        String other = login(account.getEmail(), OLD);
        HttpResponse<String> changed = change(current, OLD, NEXT);
        assertEquals(204, changed.statusCode());
        assertEquals("", changed.body());
        assertTrue(changed.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals("no-store", changed.headers().firstValue("Cache-Control").orElseThrow());
        UserAccount after = users.selectById(account.getId());
        assertEquals(2L, after.getAuthVersion());
        assertEquals(1, after.getVersion());
        assertTrue(after.getEmailVerified());
        assertEquals(account.getAccountStatus(), after.getAccountStatus());
        assertTrue(passwords.matches(NEXT, after.getPasswordHash()));
        assertFalse(passwords.matches(OLD, after.getPasswordHash()));
        assertNull(sessions.find(current));
        assertNull(sessions.find(other));
        assertEquals(401, request("GET", "/__change_it/me", current, null).statusCode());
        assertEquals(401, request("GET", "/__change_it/me", other, null).statusCode());
        assertEquals(401, change(current, OLD, NEXT).statusCode());
        assertEquals(401, loginResponse(account.getEmail(), OLD).statusCode());
        assertEquals(401, loginResponse(account.getEmail(), NEXT.trim()).statusCode());
        String fresh = login(account.getEmail(), NEXT);
        assertEquals(200, request("GET", "/__change_it/me", fresh, null).statusCode());

        var docs = request("GET", "/v3/api-docs", null, null);
        assertEquals(200, docs.statusCode());
        Map<String, Object> operation = JsonPath.read(docs.body(), "$.paths['/api/v1/me/password'].put");
        assertEquals("changePassword", operation.get("operationId"));
        assertTrue(((Map<?, ?>) operation.get("responses")).containsKey("204"));
        assertTrue(((List<?>) operation.get("security")).stream().anyMatch(value -> ((Map<?, ?>) value).containsKey("bearerAuth")));
        assertTrue(!operation.containsKey("parameters") || ((List<?>) operation.get("parameters")).isEmpty());
    }

    @Test
    void wrongCurrentPasswordKeepsDatabaseAndCurrentSessionUnchanged() throws Exception {
        UserAccount account = fixture();
        String current = login(account.getEmail(), OLD);
        var response = change(current, "wrong", NEXT);
        assertEquals(401, response.statusCode());
        assertEquals("AUTHENTICATION_FAILED", JsonPath.read(response.body(), "$.code"));
        assertEquals(account.getPasswordHash(), users.selectById(account.getId()).getPasswordHash());
        assertEquals(1L, users.selectById(account.getId()).getAuthVersion());
        assertNotNull(sessions.find(current));
        assertEquals(200, request("GET", "/__change_it/me", current, null).statusCode());
    }

    @ParameterizedTest
    @EnumSource(value = UserAccount.Status.class, names = {"WRITE_BLOCKED", "DISABLED"})
    void accountRestrictionsAreRespected(UserAccount.Status status) throws Exception {
        UserAccount account = fixture();
        String current = login(account.getEmail(), OLD);
        jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", status.name(), account.getId());
        assertEquals(status == UserAccount.Status.DISABLED ? 403 : 204, change(current, OLD, NEXT).statusCode());
        UserAccount after = users.selectById(account.getId());
        assertEquals(status, after.getAccountStatus());
        assertEquals(status == UserAccount.Status.DISABLED ? 1L : 2L, after.getAuthVersion());
    }

    @Test
    void changedCredentialVersionDuringHashCannotBeOverwritten() throws Exception {
        UserAccount account = fixture();
        String current = login(account.getEmail(), OLD);
        String winningHash = passwords.encode(" ConcurrentPassword! ");
        doAnswer(invocation -> {
            jdbc.update("UPDATE app_user SET password_hash=?, auth_version=auth_version+1 WHERE id=?", winningHash, account.getId());
            return invocation.callRealMethod();
        }).when(passwords).encode(NEXT);
        var response = change(current, OLD, NEXT);
        assertEquals(401, response.statusCode());
        assertEquals("AUTHENTICATION_REQUIRED", JsonPath.read(response.body(), "$.code"));
        assertEquals(winningHash, users.selectById(account.getId()).getPasswordHash());
        assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
    }

    @Test
    void changedHashWithoutVersionBumpCannotBeOverwritten() throws Exception {
        UserAccount account = fixture();
        String current = login(account.getEmail(), OLD);
        String winningHash = passwords.encode(" ConcurrentPassword! ");
        doAnswer(invocation -> {
            jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", winningHash, account.getId());
            return invocation.callRealMethod();
        }).when(passwords).encode(NEXT);
        assertEquals(401, change(current, OLD, NEXT).statusCode());
        assertEquals(winningHash, users.selectById(account.getId()).getPasswordHash());
        assertEquals(1L, users.selectById(account.getId()).getAuthVersion());
    }

    @Test
    void databaseWriteFailureLeavesPasswordAndAuthVersionUnchanged() {
        UserAccount account = fixture();
        assertThrows(DataAccessException.class,
                () -> transactions.change(account.getId(), 1, account.getPasswordHash(), "x".repeat(300)));
        assertEquals(account.getPasswordHash(), users.selectById(account.getId()).getPasswordHash());
        assertEquals(1L, users.selectById(account.getId()).getAuthVersion());
    }

    @Test
    void cleanupFailureAfterCommitStillMakesOldTokenUnusable() throws Exception {
        UserAccount account = fixture();
        String current = login(account.getEmail(), OLD);
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(sessions).revokeBeforeVersion(account.getId(), 2);
        assertEquals(204, change(current, OLD, NEXT).statusCode());
        assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
        assertTrue(redis.hasKey(PREFIX + "{sessions}:token:" + TokenDigest.sha256(current)));
        assertEquals(401, request("GET", "/__change_it/me", current, null).statusCode());
        assertEquals(200, request("GET", "/__change_it/me", login(account.getEmail(), NEXT), null).statusCode());
    }

    @Test
    void cleanupKeepsLoginCreatedAfterPasswordChangeCommit() throws Exception {
        UserAccount account = fixture();
        String current = login(account.getEmail(), OLD);
        AtomicReference<String> fresh = new AtomicReference<>();
        doAnswer(invocation -> {
            assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
            fresh.set(logins.login(new LoginRequest(account.getEmail(), NEXT, "during-cleanup"), "127.0.0.2").accessToken());
            return invocation.callRealMethod();
        }).when(sessions).revokeBeforeVersion(account.getId(), 2);
        assertEquals(204, change(current, OLD, NEXT).statusCode());
        assertNotNull(fresh.get());
        assertEquals(200, request("GET", "/__change_it/me", fresh.get(), null).statusCode());
        assertEquals(401, request("GET", "/__change_it/me", current, null).statusCode());
    }

    @Test
    void concurrentChangesWithSameOldCredentialsSucceedOnce() throws Exception {
        UserAccount account = fixture();
        String current = login(account.getEmail(), OLD);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> call = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return change(current, OLD, NEXT).statusCode();
        };
        try {
            Future<Integer> first = pool.submit(call);
            Future<Integer> second = pool.submit(call);
            start.countDown();
            List<Integer> result = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(result, 204));
            assertEquals(1, Collections.frequency(result, 401));
            assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void userAndSourceLimitsHaveIndependentCounters() {
        String prefix = PREFIX + "quota:";
        PasswordChangeRateLimiter limiter = new PasswordChangeRateLimiter(redis, new PasswordChangeProperties(30, 20, prefix));
        for (int i = 0; i < 20; i++) limiter.acquire("same-user", "user-source");
        BusinessException user = assertThrows(BusinessException.class, () -> limiter.acquire("same-user", "other-source"));
        assertEquals(ErrorCode.RATE_LIMITED, user.errorCode());
        assertTrue(user.retryAfterSeconds() > 0 && user.retryAfterSeconds() <= 3600);
        for (int i = 0; i < 30; i++) limiter.acquire("user" + i, "source-limit");
        BusinessException source = assertThrows(BusinessException.class, () -> limiter.acquire("new-user", "source-limit"));
        assertEquals(ErrorCode.RATE_LIMITED, source.errorCode());
        assertTrue(source.retryAfterSeconds() > 0 && source.retryAfterSeconds() <= 60);
        assertFalse(redis.hasKey(prefix + "{password-change}:user:" + TokenDigest.sha256("new-user")));
    }

    private UserAccount fixture() {
        UserAccount account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("change-flow-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        account.setPasswordHash(passwords.encode(OLD));
        fixtureIds.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private String login(String email, String password) throws Exception {
        var response = loginResponse(email, password);
        assertEquals(201, response.statusCode());
        return JsonPath.read(response.body(), "$.data.accessToken");
    }

    private HttpResponse<String> loginResponse(String email, String password) throws Exception {
        return request("POST", "/api/v1/auth/sessions", null, Map.of("email", email, "password", password));
    }

    private HttpResponse<String> change(String token, String old, String next) throws Exception {
        return request("PUT", "/api/v1/me/password", token, Map.of("currentPassword", old, "newPassword", next));
    }

    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/__change_it/me")
        CurrentUser read(@AuthenticationPrincipal SessionPrincipal principal) { return principal.user(); }
    }
}
