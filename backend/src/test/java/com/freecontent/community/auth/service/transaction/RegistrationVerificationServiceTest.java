package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.AuthTestSupport;
import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationVerificationServiceTest {
    private final EmailChallengeMapper challenges = mock(EmailChallengeMapper.class);
    private final EmailDeliveryTaskMapper tasks = mock(EmailDeliveryTaskMapper.class);
    private final EmailChallengeCrypto crypto = new EmailChallengeCrypto(AuthTestSupport.properties());
    private final Instant instant = Instant.parse("2026-11-05T00:00:00Z");
    private final RegistrationVerificationService verification = new RegistrationVerificationService(
            new EmailChallengeVerificationService(challenges, tasks, crypto, Clock.fixed(instant, ZoneOffset.UTC)),
            AuthTestSupport.registrationProperties());
    private final EmailChallenge challenge = new EmailChallenge();
    private final RegisterRequest request = new RegisterRequest("a@example.test",
            "RegistrationPassword!", "challenge1", "123456", "创作者");

    @BeforeEach
    void prepare() {
        challenge.setId("challenge1");
        challenge.setEmailNormalized("a@example.test");
        challenge.setStatus(EmailChallenge.Status.ACTIVE);
        challenge.setPurpose(EmailChallengePurpose.REGISTER);
        challenge.setFailedAttempts(0);
        challenge.setExpiresAt(LocalDateTime.ofInstant(instant.plusSeconds(600), ZoneOffset.UTC));
        challenge.setMacKeyId(crypto.macKeyId());
        challenge.setCodeMac(crypto.digest("challenge1", "a@example.test",
                EmailChallengePurpose.REGISTER, "123456"));
        when(challenges.findByIdForUpdate("challenge1")).thenReturn(challenge);
    }

    @Test
    void correctCodeIsNotConsumedDuringPreflight() {
        assertTrue(verification.verify(request, "a@example.test"));
        verify(challenges, never()).consumeRegistration(anyString(), anyInt(), any());
        verifyNoInteractions(tasks);
    }

    @Test
    void crossEmailAndCrossPurposeDoNotSpendVictimsAttempts() {
        assertFalse(verification.verify(request, "b@example.test"));
        challenge.setPurpose(EmailChallengePurpose.PASSWORD_RESET);
        assertFalse(verification.verify(request, "a@example.test"));
        verify(challenges, never()).recordFailedAttempt(anyString(), anyInt(), any());
    }

    @Test
    void fifthWrongCodeLocksAndCancelsOutstandingMail() {
        challenge.setFailedAttempts(4);
        challenge.setCodeMac(crypto.digest("challenge1", "a@example.test",
                EmailChallengePurpose.REGISTER, "999999"));
        when(challenges.recordFailedAttempt(eq("challenge1"), eq(5), any())).thenReturn(1);
        assertFalse(verification.verify(request, "a@example.test"));
        verify(tasks).cancelAfterVerification(eq("challenge1"), eq("CHALLENGE_LOCKED"), any());
    }

    @Test
    void expiredOrConsumedChallengeCannotBeUsed() {
        challenge.setExpiresAt(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        assertFalse(verification.verify(request, "a@example.test"));
        challenge.setExpiresAt(LocalDateTime.ofInstant(instant.plusSeconds(600), ZoneOffset.UTC));
        challenge.setStatus(EmailChallenge.Status.CONSUMED);
        assertFalse(verification.verify(request, "a@example.test"));
        verify(challenges, never()).recordFailedAttempt(anyString(), anyInt(), any());
    }
}
