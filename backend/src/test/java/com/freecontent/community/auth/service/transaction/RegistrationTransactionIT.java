package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/** 显式运行，使用真实MySQL验证消费、计数、并发和事务回滚。 */
@SpringBootTest(properties = "app.auth.email-challenge.delivery-enabled=false")
class RegistrationTransactionIT {
    @Autowired private RegistrationVerificationService verification;
    @Autowired private RegistrationTransactionService transactions;
    @Autowired private EmailChallengeMapper challenges;
    @Autowired private UserAccountMapper users;
    @Autowired private EmailChallengeCrypto crypto;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmailDeliveryTaskMapper tasks;
    private final Set<String> fixtureEmails = new HashSet<>();
    private final List<String> fixtureChallengeIds = new ArrayList<>();
    private static final String PASSWORD = " IntegrationPassword! ";

    @AfterEach
    void cleanFixtures() {
        for (String email : fixtureEmails) {
            jdbc.update("""
                    DELETE r FROM app_user_role r JOIN app_user u ON u.id=r.user_id
                    WHERE u.email_normalized=?
                    """, email);
            jdbc.update("DELETE FROM app_user WHERE email_normalized=?", email);
        }
        for (String id : fixtureChallengeIds) {
            challenges.deleteById(id);
        }
    }

    @Test
    void correctCodeCreatesVerifiedUserAndCannotBeReplayed() {
        EmailChallenge challenge = fixture(null, EmailChallengePurpose.REGISTER);
        String hash = encoder.encode(PASSWORD);
        RegisterRequest request = request(challenge, "123456");
        var result = transactions.register(request, challenge.getEmailNormalized(), hash);
        assertEquals(RegistrationTransactionService.Status.REGISTERED, result.status());
        assertTrue(result.user().emailVerified());
        assertTrue(result.user().canCreate());
        assertEquals(List.of(UserRole.USER), result.user().roles());
        assertEquals(1, result.user().version());
        assertTrue(encoder.matches(PASSWORD, users.findByEmail(challenge.getEmailNormalized()).getPasswordHash()));
        assertEquals(EmailChallenge.Status.CONSUMED, challenges.selectById(challenge.getId()).getStatus());
        assertEquals(RegistrationTransactionService.Status.INVALID_CHALLENGE,
                transactions.register(request, challenge.getEmailNormalized(), hash).status());
    }

    @Test
    void wrongCodeCounterCommitsAndLocksExactlyAtFive() {
        EmailChallenge challenge = fixture(null, EmailChallengePurpose.REGISTER);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertFalse(verification.verify(request(challenge, "000000"), challenge.getEmailNormalized()));
            EmailChallenge current = challenges.selectById(challenge.getId());
            assertEquals(attempt, current.getFailedAttempts());
            assertEquals(attempt < 5 ? EmailChallenge.Status.ACTIVE : EmailChallenge.Status.LOCKED,
                    current.getStatus());
        }
        assertFalse(verification.verify(request(challenge, "123456"), challenge.getEmailNormalized()));
        assertNull(users.findByEmail(challenge.getEmailNormalized()));
    }

    @Test
    void failureAfterConsumptionRollsBackUserRolesAndCode() {
        EmailChallenge challenge = fixture(null, EmailChallengePurpose.REGISTER);
        doThrow(new DataIntegrityViolationException("injected post-consumption failure"))
                .when(tasks).cancelAfterVerification(eq(challenge.getId()), eq("CHALLENGE_CONSUMED"), any());
        assertThrows(DataIntegrityViolationException.class, () -> transactions.register(
                request(challenge, "123456"), challenge.getEmailNormalized(), encoder.encode(PASSWORD)));
        assertNull(users.findByEmail(challenge.getEmailNormalized()));
        EmailChallenge current = challenges.selectById(challenge.getId());
        assertEquals(EmailChallenge.Status.ACTIVE, current.getStatus());
        assertNull(current.getConsumedAt());
    }

    @Test
    void duplicateEmailDoesNotConsumeNewChallenge() {
        EmailChallenge first = fixture(null, EmailChallengePurpose.REGISTER);
        String hash = encoder.encode(PASSWORD);
        assertEquals(RegistrationTransactionService.Status.REGISTERED,
                transactions.register(request(first, "123456"), first.getEmailNormalized(), hash).status());
        EmailChallenge second = fixture(first.getEmailNormalized(), EmailChallengePurpose.REGISTER);
        assertEquals(RegistrationTransactionService.Status.EMAIL_UNAVAILABLE,
                transactions.register(request(second, "123456"), second.getEmailNormalized(), hash).status());
        assertEquals(EmailChallenge.Status.ACTIVE, challenges.selectById(second.getId()).getStatus());
    }

    @Test
    void concurrentRegistrationsCreateExactlyOneAccount() throws Exception {
        EmailChallenge challenge = fixture(null, EmailChallengePurpose.REGISTER);
        String hash = encoder.encode(PASSWORD);
        RegisterRequest request = request(challenge, "123456");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<RegistrationTransactionService.Status> register = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return transactions.register(request, challenge.getEmailNormalized(), hash).status();
        };
        try {
            Future<RegistrationTransactionService.Status> first = pool.submit(register);
            Future<RegistrationTransactionService.Status> second = pool.submit(register);
            start.countDown();
            List<RegistrationTransactionService.Status> statuses = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(statuses, RegistrationTransactionService.Status.REGISTERED));
            assertEquals(1, Collections.frequency(statuses, RegistrationTransactionService.Status.INVALID_CHALLENGE));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email_normalized=?",
                    Integer.class, challenge.getEmailNormalized()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void preflightCannotAuthorizeLaterExpiredOrReplacedChallenge() {
        EmailChallenge challenge = fixture(null, EmailChallengePurpose.REGISTER);
        RegisterRequest request = request(challenge, "123456");
        assertTrue(verification.verify(request, challenge.getEmailNormalized()));
        jdbc.update("UPDATE email_challenge SET expires_at=? WHERE id=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), challenge.getId());
        assertEquals(RegistrationTransactionService.Status.INVALID_CHALLENGE,
                transactions.register(request, challenge.getEmailNormalized(), encoder.encode(PASSWORD)).status());
        assertNull(users.findByEmail(challenge.getEmailNormalized()));
    }

    @Test
    void resetPurposeAndWrongEmailCannotRegisterOrSpendAttempts() {
        EmailChallenge reset = fixture(null, EmailChallengePurpose.PASSWORD_RESET);
        assertFalse(verification.verify(request(reset, "123456"), reset.getEmailNormalized()));
        EmailChallenge registration = fixture(null, EmailChallengePurpose.REGISTER);
        assertFalse(verification.verify(request(registration, "123456"), "another@example.test"));
        assertEquals(0, challenges.selectById(reset.getId()).getFailedAttempts());
        assertEquals(0, challenges.selectById(registration.getId()).getFailedAttempts());
    }

    private EmailChallenge fixture(String existingEmail, EmailChallengePurpose purpose) {
        String email = existingEmail == null
                ? "registration-" + UUID.randomUUID().toString().replace("-", "") + "@example.test"
                : existingEmail;
        EmailChallenge result = new EmailChallenge();
        result.setId(UUID.randomUUID().toString().replace("-", ""));
        result.setEmailNormalized(email);
        result.setDeliveryEmail(email);
        result.setPurpose(purpose);
        result.setCodeMac(crypto.digest(result.getId(), email, purpose, "123456"));
        result.setMacKeyId(crypto.macKeyId());
        result.setStatus(EmailChallenge.Status.ACTIVE);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        result.setExpiresAt(now.plusSeconds(600));
        result.setResendAfter(now.plusSeconds(60));
        challenges.insert(result);
        fixtureEmails.add(email);
        fixtureChallengeIds.add(result.getId());
        return result;
    }

    private RegisterRequest request(EmailChallenge challenge, String code) {
        return new RegisterRequest(challenge.getEmailNormalized().toUpperCase(Locale.ROOT),
                PASSWORD, challenge.getId(), code, "注册测试");
    }
}
