package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.dto.ResetPasswordRequest;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "app.auth.email-challenge.delivery-enabled=false")
class PasswordResetTransactionIT {
    private static final String OLD_PASSWORD = " PreviousPassword! ";
    private static final String NEW_PASSWORD = " NewPasswordReset! ";
    @Autowired private PasswordResetTransactionService transactions;
    @Autowired private EmailChallengeVerificationService verification;
    @Autowired private EmailChallengeMapper challenges;
    @Autowired private UserAccountMapper users;
    @Autowired private EmailChallengeCrypto crypto;
    @Autowired private PasswordHasher passwords;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmailDeliveryTaskMapper tasks;
    private final List<String> fixtureUserIds = new ArrayList<>();
    private final List<String> fixtureChallengeIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : fixtureChallengeIds) challenges.deleteById(id);
        for (String id : fixtureUserIds) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void resetUpdatesPasswordAndAuthVersionAndConsumesChallengeExactlyOnce() {
        UserAccount account = account(null);
        EmailChallenge challenge = challenge(account.getEmailNormalized(), EmailChallengePurpose.PASSWORD_RESET);
        var result = transactions.reset(request(challenge, "000123"), account.getEmailNormalized(), passwords.encode(NEW_PASSWORD));
        assertEquals(PasswordResetTransactionService.Status.RESET, result.status());
        assertEquals(account.getId(), result.userId());
        assertEquals(2L, result.authVersion());
        UserAccount current = users.selectById(account.getId());
        assertTrue(passwords.matches(NEW_PASSWORD, current.getPasswordHash()));
        assertFalse(passwords.matches(OLD_PASSWORD, current.getPasswordHash()));
        assertEquals(2L, current.getAuthVersion());
        assertEquals(1, current.getVersion());
        assertEquals(account.getAccountStatus(), current.getAccountStatus());
        assertEquals(account.getEmailVerified(), current.getEmailVerified());
        assertEquals(EmailChallenge.Status.CONSUMED, challenges.selectById(challenge.getId()).getStatus());
        assertNotNull(challenges.selectById(challenge.getId()).getConsumedAt());
        verify(tasks).cancelAfterVerification(eq(challenge.getId()), eq("CHALLENGE_CONSUMED"), any());
        assertEquals(PasswordResetTransactionService.Status.INVALID_CHALLENGE,
                transactions.reset(request(challenge, "000123"), account.getEmailNormalized(), "unused").status());
        assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
    }

    @Test
    void correctCodeForUnknownEmailIsConsumedAndCannotResetALaterCreatedAccount() {
        EmailChallenge challenge = challenge(null, EmailChallengePurpose.PASSWORD_RESET);
        var result = transactions.reset(request(challenge, "000123"), challenge.getEmailNormalized(), passwords.encode(NEW_PASSWORD));
        assertEquals(PasswordResetTransactionService.Status.NO_ACCOUNT, result.status());
        assertNull(result.userId());
        assertNull(users.findByEmail(challenge.getEmailNormalized()));
        assertEquals(EmailChallenge.Status.CONSUMED, challenges.selectById(challenge.getId()).getStatus());
        UserAccount createdLater = account(challenge.getEmailNormalized());
        assertEquals(PasswordResetTransactionService.Status.INVALID_CHALLENGE,
                transactions.reset(request(challenge, "000123"), challenge.getEmailNormalized(), "unused").status());
        assertEquals(createdLater.getPasswordHash(), users.selectById(createdLater.getId()).getPasswordHash());
    }

    @Test
    void wrongCodeCountsCommitAndFifthAttemptLocksWithoutChangingPassword() {
        UserAccount account = account(null);
        EmailChallenge challenge = challenge(account.getEmailNormalized(), EmailChallengePurpose.PASSWORD_RESET);
        for (int i = 1; i <= 5; i++) {
            assertEquals(PasswordResetTransactionService.Status.INVALID_CHALLENGE,
                    transactions.reset(request(challenge, "999999"), account.getEmailNormalized(), "unused").status());
            EmailChallenge current = challenges.selectById(challenge.getId());
            assertEquals(i, current.getFailedAttempts());
            assertEquals(i < 5 ? EmailChallenge.Status.ACTIVE : EmailChallenge.Status.LOCKED, current.getStatus());
        }
        assertEquals(account.getPasswordHash(), users.selectById(account.getId()).getPasswordHash());
        assertEquals(1L, users.selectById(account.getId()).getAuthVersion());
        assertEquals(PasswordResetTransactionService.Status.INVALID_CHALLENGE,
                transactions.reset(request(challenge, "000123"), account.getEmailNormalized(), "unused").status());
    }

    @Test
    void failureAfterConsumptionRollsBackPasswordVersionAndChallenge() {
        UserAccount account = account(null);
        EmailChallenge challenge = challenge(account.getEmailNormalized(), EmailChallengePurpose.PASSWORD_RESET);
        doThrow(new DataIntegrityViolationException("injected post-consumption failure"))
                .when(tasks).cancelAfterVerification(eq(challenge.getId()), eq("CHALLENGE_CONSUMED"), any());
        String newHash = passwords.encode(NEW_PASSWORD);
        assertThrows(DataIntegrityViolationException.class,
                () -> transactions.reset(request(challenge, "000123"), account.getEmailNormalized(), newHash));
        assertEquals(account.getPasswordHash(), users.selectById(account.getId()).getPasswordHash());
        assertEquals(1L, users.selectById(account.getId()).getAuthVersion());
        EmailChallenge unchanged = challenges.selectById(challenge.getId());
        assertEquals(EmailChallenge.Status.ACTIVE, unchanged.getStatus());
        assertNull(unchanged.getConsumedAt());
        assertEquals(0, unchanged.getFailedAttempts());
    }

    @Test
    void challengeExpiredOrReplacedAfterPrecheckCannotResetPassword() {
        UserAccount account = account(null);
        EmailChallenge challenge = challenge(account.getEmailNormalized(), EmailChallengePurpose.PASSWORD_RESET);
        assertTrue(verification.verify(challenge.getId(), account.getEmailNormalized(),
                EmailChallengePurpose.PASSWORD_RESET, "000123", 5));
        jdbc.update("UPDATE email_challenge SET expires_at=? WHERE id=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), challenge.getId());
        assertEquals(PasswordResetTransactionService.Status.INVALID_CHALLENGE,
                transactions.reset(request(challenge, "000123"), account.getEmailNormalized(), "unused").status());
        jdbc.update("UPDATE email_challenge SET expires_at=?, status='INVALIDATED' WHERE id=?",
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(600), challenge.getId());
        assertEquals(PasswordResetTransactionService.Status.INVALID_CHALLENGE,
                transactions.reset(request(challenge, "000123"), account.getEmailNormalized(), "unused").status());
        assertEquals(account.getPasswordHash(), users.selectById(account.getId()).getPasswordHash());
    }

    @Test
    void wrongPurposeOrEmailCannotResetPasswordOrSpendOtherChallengesAttempts() {
        UserAccount account = account(null);
        EmailChallenge registration = challenge(account.getEmailNormalized(), EmailChallengePurpose.REGISTER);
        assertEquals(PasswordResetTransactionService.Status.INVALID_CHALLENGE,
                transactions.reset(request(registration, "000123"), account.getEmailNormalized(), "unused").status());
        EmailChallenge reset = challenge(account.getEmailNormalized(), EmailChallengePurpose.PASSWORD_RESET);
        assertEquals(PasswordResetTransactionService.Status.INVALID_CHALLENGE,
                transactions.reset(request(reset, "000123"), "another@example.test", "unused").status());
        assertEquals(0, challenges.selectById(registration.getId()).getFailedAttempts());
        assertEquals(0, challenges.selectById(reset.getId()).getFailedAttempts());
        assertEquals(account.getPasswordHash(), users.selectById(account.getId()).getPasswordHash());
    }

    @Test
    void concurrentUseOfOneChallengeResetsExactlyOnce() throws Exception {
        UserAccount account = account(null);
        EmailChallenge challenge = challenge(account.getEmailNormalized(), EmailChallengePurpose.PASSWORD_RESET);
        String hash = passwords.encode(NEW_PASSWORD);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<PasswordResetTransactionService.Status> call = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return transactions.reset(request(challenge, "000123"), account.getEmailNormalized(), hash).status();
        };
        try {
            Future<PasswordResetTransactionService.Status> first = pool.submit(call);
            Future<PasswordResetTransactionService.Status> second = pool.submit(call);
            start.countDown();
            List<PasswordResetTransactionService.Status> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(results, PasswordResetTransactionService.Status.RESET));
            assertEquals(1, Collections.frequency(results, PasswordResetTransactionService.Status.INVALID_CHALLENGE));
            assertEquals(2L, users.selectById(account.getId()).getAuthVersion());
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private UserAccount account(String email) {
        UserAccount user = LoginTestSupport.account();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail(email == null ? "reset-tx-" + user.getId() + "@example.test" : email);
        user.setEmailNormalized(user.getEmail());
        user.setPasswordHash(passwords.encode(OLD_PASSWORD));
        fixtureUserIds.add(user.getId());
        users.insert(user);
        return user;
    }

    private EmailChallenge challenge(String email, EmailChallengePurpose purpose) {
        EmailChallenge challenge = new EmailChallenge();
        challenge.setId(UUID.randomUUID().toString().replace("-", ""));
        challenge.setEmailNormalized(email == null ? "reset-tx-" + challenge.getId() + "@example.test" : email);
        challenge.setDeliveryEmail(challenge.getEmailNormalized());
        challenge.setPurpose(purpose);
        challenge.setStatus(EmailChallenge.Status.ACTIVE);
        challenge.setFailedAttempts(0);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        challenge.setCreatedAt(now);
        challenge.setUpdatedAt(now);
        challenge.setExpiresAt(now.plusSeconds(600));
        challenge.setResendAfter(now.plusSeconds(60));
        challenge.setMacKeyId(crypto.macKeyId());
        challenge.setCodeMac(crypto.digest(challenge.getId(), challenge.getEmailNormalized(), purpose, "000123"));
        fixtureChallengeIds.add(challenge.getId());
        challenges.insert(challenge);
        return challenge;
    }

    private ResetPasswordRequest request(EmailChallenge challenge, String code) {
        return new ResetPasswordRequest(challenge.getEmailNormalized(), challenge.getId(), code, NEW_PASSWORD);
    }
}
