package com.freecontent.community.auth.service;

import com.freecontent.community.auth.AuthTestSupport;
import com.freecontent.community.auth.dto.CreateEmailChallengeRequest;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailChallengeServiceTest {

    private final EmailChallengeRateLimiter limiter = mock(EmailChallengeRateLimiter.class);
    private final EmailChallengeWriter writer = mock(EmailChallengeWriter.class);
    private final EmailChallengeCrypto crypto = new EmailChallengeCrypto(AuthTestSupport.properties());
    private final EmailChallengeService service = new EmailChallengeService(
            limiter, crypto, writer, AuthTestSupport.properties());

    @Test
    void normalizesIdentityButPreservesDeliveryAddressAndEncryptsCode() {
        Instant issuedAt = Instant.parse("2026-11-05T00:00:00Z");
        when(limiter.acquire("creator@example.test", EmailChallengePurpose.REGISTER, "192.0.2.1"))
                .thenReturn(issuedAt);
        var receipt = service.create(new CreateEmailChallengeRequest(
                EmailChallengePurpose.REGISTER, " Creator@Example.test "), "192.0.2.1");
        ArgumentCaptor<EmailChallenge> challenge = ArgumentCaptor.forClass(EmailChallenge.class);
        ArgumentCaptor<EmailDeliveryTask> task = ArgumentCaptor.forClass(EmailDeliveryTask.class);
        verify(writer).save(challenge.capture(), task.capture());
        assertEquals("creator@example.test", challenge.getValue().getEmailNormalized());
        assertEquals("Creator@Example.test", challenge.getValue().getDeliveryEmail());
        String code = crypto.decrypt(task.getValue().getEncryptionKeyId(), task.getValue().getId(),
                receipt.challengeId(), task.getValue().getCodeCiphertext(), task.getValue().getEncryptionIv());
        assertTrue(crypto.matches(challenge.getValue().getMacKeyId(), challenge.getValue().getCodeMac(),
                receipt.challengeId(), "creator@example.test", EmailChallengePurpose.REGISTER, code));
        assertEquals(issuedAt.plusSeconds(600), receipt.expiresAt());
        assertEquals(receipt.expiresAt(), challenge.getValue().getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Test
    void dependencyFailureCannotReturnAcceptedReceipt() {
        when(limiter.acquire(anyString(), any(), anyString())).thenReturn(Instant.now());
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(writer).save(any(), any());
        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                new CreateEmailChallengeRequest(EmailChallengePurpose.REGISTER, "a@example.test"), "127.0.0.1"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
    }
}
