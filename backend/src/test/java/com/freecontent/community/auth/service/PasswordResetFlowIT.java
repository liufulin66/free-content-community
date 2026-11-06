package com.freecontent.community.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.PasswordResetProperties;
import com.freecontent.community.auth.dto.LoginRequest;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import com.freecontent.community.auth.mapper.*;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.ratelimit.PasswordResetRateLimiter;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.session.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实HTTP、MySQL、Redis；后台邮件投递关闭，仅操作本类唯一前缀和账号。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.email-challenge.delivery-enabled=false")
@Import(PasswordResetFlowIT.ProbeController.class)
class PasswordResetFlowIT {
    private static final String PREFIX = "test:password-reset-it:" + UUID.randomUUID() + ":";
    private static final String OLD_PASSWORD = " PreviousPassword! ";
    private static final String NEW_PASSWORD = " NewPasswordReset! ";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private EmailChallengeMapper challenges;
    @Autowired private EmailDeliveryTaskMapper tasks;
    @Autowired private PasswordHasher passwords;
    @Autowired private EmailChallengeCrypto crypto;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private LoginService logins;
    @MockitoSpyBean private RedisSessionStore sessions;
    private final Set<String> fixtureUserIds = new HashSet<>();
    private final Set<String> fixtureChallengeIds = new HashSet<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void prefixes(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.auth.password-reset.redis-key-prefix", () -> PREFIX);
        registry.add("app.auth.email-challenge.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : fixtureChallengeIds) {
            tasks.delete(Wrappers.lambdaQuery(EmailDeliveryTask.class).eq(EmailDeliveryTask::getChallengeId, id));
            challenges.deleteById(id);
        }
        for (String id : fixtureUserIds) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void resetConsumesChallengeCancelsMailAndInvalidatesAllOldPasswordsAndSessions() throws Exception {
        UserAccount account = account();
        String firstToken = token(login(account.getEmail(), OLD_PASSWORD));
        String secondToken = token(login(account.getEmail(), OLD_PASSWORD));
        Challenge challenge = challenge(account.getEmail(), EmailChallengePurpose.PASSWORD_RESET);
        HttpResponse<String> response = reset(challenge, NEW_PASSWORD);
        assertEquals(204, response.statusCode());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        UserAccount current = users.selectById(account.getId());
        assertTrue(passwords.matches(NEW_PASSWORD, current.getPasswordHash()));
        assertFalse(passwords.matches(NEW_PASSWORD.trim(), current.getPasswordHash()));
        assertEquals(2L, current.getAuthVersion());
        assertEquals(1, current.getVersion());
        assertTrue(current.getEmailVerified());
        assertEquals(account.getAccountStatus(), current.getAccountStatus());
        assertEquals(EmailChallenge.Status.CONSUMED, challenges.selectById(challenge.id()).getStatus());
        EmailDeliveryTask task = tasks.selectById(challenge.taskId());
        assertEquals(EmailDeliveryTask.Status.CANCELLED, task.getStatus());
        assertNull(task.getCodeCiphertext());
        assertNull(task.getEncryptionIv());
        assertNull(sessions.find(firstToken));
        assertNull(sessions.find(secondToken));
        assertFalse(redis.hasKey(PREFIX + "{sessions}:user:" + account.getId()));
        assertEquals(401, request("GET", "/__reset_it/me", firstToken).statusCode());
        assertEquals(401, request("GET", "/__reset_it/me", secondToken).statusCode());
        assertEquals(401, login(account.getEmail(), OLD_PASSWORD).statusCode());
        assertEquals(401, login(account.getEmail(), NEW_PASSWORD.trim()).statusCode());
        String freshToken = token(login(account.getEmail(), NEW_PASSWORD));
        assertEquals(200, request("GET", "/__reset_it/me", freshToken).statusCode());
        assertEquals(400, reset(challenge, NEW_PASSWORD).statusCode());

        HttpResponse<String> docs = request("GET", "/v3/api-docs", null);
        assertEquals(200, docs.statusCode());
        Map<String, Object> operation = JsonPath.read(docs.body(), "$.paths['/api/v1/auth/password-resets'].post");
        assertEquals("resetPassword", operation.get("operationId"));
        assertTrue(((Map<?, ?>) operation.get("responses")).containsKey("204"));
        assertTrue(!operation.containsKey("security") || ((List<?>) operation.get("security")).isEmpty());
    }

    @Test
    void unknownAccountGetsSame204AndItsChallengeCannotBeReplayed() throws Exception {
        String email = "reset-unknown-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
        Challenge challenge = challenge(email, EmailChallengePurpose.PASSWORD_RESET);
        assertEquals(204, reset(challenge, NEW_PASSWORD).statusCode());
        assertNull(users.findByEmail(email));
        assertEquals(EmailChallenge.Status.CONSUMED, challenges.selectById(challenge.id()).getStatus());
        assertEquals(400, reset(challenge, NEW_PASSWORD).statusCode());
    }

    @Test
    void wrongCodeResponsesCommitCountersAndLockAfterFive() throws Exception {
        UserAccount account = account();
        Challenge challenge = challenge(account.getEmail(), EmailChallengePurpose.PASSWORD_RESET);
        String wrong = challenge.code().equals("999999") ? "888888" : "999999";
        for (int i = 1; i <= 5; i++) {
            HttpResponse<String> response = post("/api/v1/auth/password-resets", resetBody(challenge, wrong, NEW_PASSWORD), null);
            assertEquals(400, response.statusCode());
            assertEquals("CHALLENGE_INVALID", JsonPath.read(response.body(), "$.code"));
            EmailChallenge current = challenges.selectById(challenge.id());
            assertEquals(i, current.getFailedAttempts());
            assertEquals(i < 5 ? EmailChallenge.Status.ACTIVE : EmailChallenge.Status.LOCKED, current.getStatus());
        }
        assertEquals(400, reset(challenge, NEW_PASSWORD).statusCode());
        assertEquals(account.getPasswordHash(), users.selectById(account.getId()).getPasswordHash());
        assertEquals(1L, users.selectById(account.getId()).getAuthVersion());
        assertEquals(EmailDeliveryTask.Status.CANCELLED, tasks.selectById(challenge.taskId()).getStatus());
    }

    @Test
    void registrationCodeWrongMailboxAndExpiredCodeCannotResetPassword() throws Exception {
        UserAccount account = account();
        UserAccount other = account();
        Challenge registration = challenge(account.getEmail(), EmailChallengePurpose.REGISTER);
        assertEquals(400, reset(registration, NEW_PASSWORD).statusCode());
        Challenge reset = challenge(account.getEmail(), EmailChallengePurpose.PASSWORD_RESET);
        Map<String, String> wrongMailbox = new HashMap<>(resetBody(reset, reset.code(), NEW_PASSWORD));
        wrongMailbox.put("email", other.getEmail());
        assertEquals(400, post("/api/v1/auth/password-resets", wrongMailbox, null).statusCode());
        jdbc.update("UPDATE email_challenge SET expires_at=? WHERE id=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), reset.id());
        assertEquals(400, reset(reset, NEW_PASSWORD).statusCode());
        assertEquals(0, challenges.selectById(registration.id()).getFailedAttempts());
        assertEquals(0, challenges.selectById(reset.id()).getFailedAttempts());
        assertEquals(account.getPasswordHash(), users.selectById(account.getId()).getPasswordHash());
        assertEquals(other.getPasswordHash(), users.selectById(other.getId()).getPasswordHash());
    }

    @ParameterizedTest
    @EnumSource(value = UserAccount.Status.class, names = {"WRITE_BLOCKED", "DISABLED"})
    void resetDoesNotRemoveAccountRestrictions(UserAccount.Status status) throws Exception {
        UserAccount account = account();
        jdbc.update("UPDATE app_user SET account_status=? WHERE id=?", status.name(), account.getId());
        Challenge challenge = challenge(account.getEmail(), EmailChallengePurpose.PASSWORD_RESET);
        assertEquals(204, reset(challenge, NEW_PASSWORD).statusCode());
        assertEquals(status, users.selectById(account.getId()).getAccountStatus());
        assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
        assertEquals(status == UserAccount.Status.DISABLED ? 403 : 201, login(account.getEmail(), NEW_PASSWORD).statusCode());
    }

    @Test
    void redisCleanupFailureStillReturns204AndOldPhysicalSessionCannotAuthenticate() throws Exception {
        UserAccount account = account();
        IssuedSession old = sessions.create(account.getId(), 1, "before-reset");
        Challenge challenge = challenge(account.getEmail(), EmailChallengePurpose.PASSWORD_RESET);
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .when(sessions).revokeBeforeVersion(account.getId(), 2L);
        assertEquals(204, reset(challenge, NEW_PASSWORD).statusCode());
        assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
        assertEquals(EmailChallenge.Status.CONSUMED, challenges.selectById(challenge.id()).getStatus());
        assertTrue(redis.hasKey(PREFIX + "{sessions}:token:" + TokenDigest.sha256(old.accessToken())));
        assertEquals(401, request("GET", "/__reset_it/me", old.accessToken()).statusCode());
        String fresh = token(login(account.getEmail(), NEW_PASSWORD));
        assertEquals(200, request("GET", "/__reset_it/me", fresh).statusCode());
        assertFalse(redis.hasKey(PREFIX + "{sessions}:token:" + TokenDigest.sha256(old.accessToken())));
    }

    @Test
    void cleanupKeepsNewLoginCreatedAfterDatabaseCommit() throws Exception {
        UserAccount account = account();
        String old = token(login(account.getEmail(), OLD_PASSWORD));
        Challenge challenge = challenge(account.getEmail(), EmailChallengePurpose.PASSWORD_RESET);
        AtomicReference<String> freshToken = new AtomicReference<>();
        doAnswer(invocation -> {
            assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
            freshToken.set(logins.login(new LoginRequest(account.getEmail(), NEW_PASSWORD, "during-cleanup"), "127.0.0.2").accessToken());
            return invocation.callRealMethod();
        }).when(sessions).revokeBeforeVersion(account.getId(), 2L);
        assertEquals(204, reset(challenge, NEW_PASSWORD).statusCode());
        assertNotNull(freshToken.get());
        assertEquals(200, request("GET", "/__reset_it/me", freshToken.get()).statusCode());
        assertEquals(401, request("GET", "/__reset_it/me", old).statusCode());
    }

    @Test
    void lateOldVersionSessionCreatedAfterCleanupStillCannotAuthenticate() throws Exception {
        UserAccount account = account();
        Challenge challenge = challenge(account.getEmail(), EmailChallengePurpose.PASSWORD_RESET);
        assertEquals(204, reset(challenge, NEW_PASSWORD).statusCode());
        IssuedSession stale = sessions.create(account.getId(), 1, "late-old-login");
        assertEquals(401, request("GET", "/__reset_it/me", stale.accessToken()).statusCode());
        String fresh = token(login(account.getEmail(), NEW_PASSWORD));
        assertEquals(200, request("GET", "/__reset_it/me", fresh).statusCode());
    }

    @Test
    void resetSubmissionLimitsUseIndependentHourlyAndMinuteCounters() {
        String prefix = PREFIX + "quotas:";
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(redis, new PasswordResetProperties(5, 30, 20, prefix));
        for (int i = 0; i < 20; i++) limiter.acquire("quota@example.test", "email-limit-source");
        BusinessException email = assertThrows(BusinessException.class, () -> limiter.acquire("quota@example.test", "other-source"));
        assertEquals(ErrorCode.RATE_LIMITED, email.errorCode());
        assertTrue(email.retryAfterSeconds() > 0 && email.retryAfterSeconds() <= 3600);
        for (int i = 0; i < 30; i++) limiter.acquire("source" + i + "@example.test", "source-limit");
        BusinessException source = assertThrows(BusinessException.class, () -> limiter.acquire("new@example.test", "source-limit"));
        assertEquals(ErrorCode.RATE_LIMITED, source.errorCode());
        assertTrue(source.retryAfterSeconds() > 0 && source.retryAfterSeconds() <= 60);
        assertFalse(redis.hasKey(prefix + "{password-reset}:email:" + TokenDigest.sha256("new@example.test")));
        assertTrue(redis.keys(prefix + "{registration}:*").isEmpty());
        assertTrue(redis.keys(prefix + "{login}:*").isEmpty());
    }

    @Test
    void concurrentHttpRequestsCanConsumeOneResetChallengeOnlyOnce() throws Exception {
        UserAccount account = account();
        Challenge challenge = challenge(account.getEmail(), EmailChallengePurpose.PASSWORD_RESET);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> call = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return reset(challenge, NEW_PASSWORD).statusCode();
        };
        try {
            Future<Integer> first = pool.submit(call);
            Future<Integer> second = pool.submit(call);
            start.countDown();
            List<Integer> statuses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(statuses, 204));
            assertEquals(1, Collections.frequency(statuses, 400));
            assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private UserAccount account() {
        UserAccount account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("reset-flow-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        account.setPasswordHash(passwords.encode(OLD_PASSWORD));
        fixtureUserIds.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private Challenge challenge(String email, EmailChallengePurpose purpose) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/email-challenges", Map.of("email", " " + email.toUpperCase(Locale.ROOT) + " ",
                "purpose", purpose.name()), null);
        assertEquals(202, response.statusCode());
        String id = JsonPath.read(response.body(), "$.data.challengeId");
        fixtureChallengeIds.add(id);
        EmailDeliveryTask task = tasks.selectOne(Wrappers.lambdaQuery(EmailDeliveryTask.class).eq(EmailDeliveryTask::getChallengeId, id));
        assertNotNull(task);
        String code = crypto.decrypt(task.getEncryptionKeyId(), task.getId(), id, task.getCodeCiphertext(), task.getEncryptionIv());
        return new Challenge(id, email, code, task.getId());
    }

    private HttpResponse<String> reset(Challenge challenge, String password) throws Exception {
        return post("/api/v1/auth/password-resets", resetBody(challenge, challenge.code(), password), "old-token");
    }

    private Map<String, String> resetBody(Challenge challenge, String code, String password) {
        return Map.of("email", " " + challenge.email().toUpperCase(Locale.ROOT) + " ", "challengeId", challenge.id(),
                "code", code, "newPassword", password);
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return post("/api/v1/auth/sessions", Map.of("email", email, "password", password), null);
    }

    private String token(HttpResponse<String> response) {
        assertEquals(201, response.statusCode());
        return JsonPath.read(response.body(), "$.data.accessToken");
    }

    private HttpResponse<String> post(String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Challenge(String id, String email, String code, String taskId) {
        @Override public String toString() { return "Challenge[credentials=REDACTED]"; }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/__reset_it/me")
        CurrentUser me(@AuthenticationPrincipal SessionPrincipal principal) { return principal.user(); }
    }
}
