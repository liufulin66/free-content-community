package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailChallengeTransactionServiceTest {

    private final EmailChallengeMapper challenges = mock(EmailChallengeMapper.class);
    private final EmailDeliveryTaskMapper tasks = mock(EmailDeliveryTaskMapper.class);
    private final Instant now = Instant.parse("2026-11-05T00:02:00Z");
    private final EmailChallengeTransactionService transactions = new EmailChallengeTransactionService(
            challenges, tasks, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void delayedOlderRequestCannotInvalidateNewerChallenge() {
        EmailChallenge older = challenge("older", now.minusSeconds(90));
        EmailChallenge newer = challenge("newer", now.minusSeconds(10));
        when(challenges.findActiveForUpdate("a@example.test", EmailChallengePurpose.REGISTER))
                .thenReturn(newer);
        BusinessException error = assertThrows(BusinessException.class,
                () -> transactions.save(older, new EmailDeliveryTask()));
        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode());
        verify(challenges, never()).invalidate(anyString(), any());
        verifyNoInteractions(tasks);
    }

    @Test
    void replacingOldChallengeCancelsItsTaskAndPersistsNewPair() {
        EmailChallenge old = challenge("old", now.minusSeconds(120));
        EmailChallenge next = challenge("next", now);
        EmailDeliveryTask task = new EmailDeliveryTask();
        when(challenges.findActiveForUpdate("a@example.test", EmailChallengePurpose.REGISTER))
                .thenReturn(old);
        when(challenges.invalidate(eq("old"), any())).thenReturn(1);
        when(challenges.insert(next)).thenReturn(1);
        when(tasks.insert(task)).thenReturn(1);
        transactions.save(next, task);
        verify(tasks).cancelForChallenge(eq("old"), any());
        verify(challenges).insert(next);
        verify(tasks).insert(task);
    }

    private EmailChallenge challenge(String id, Instant createdAt) {
        EmailChallenge result = new EmailChallenge();
        result.setId(id);
        result.setEmailNormalized("a@example.test");
        result.setPurpose(EmailChallengePurpose.REGISTER);
        result.setCreatedAt(LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        result.setExpiresAt(LocalDateTime.ofInstant(createdAt.plusSeconds(600), ZoneOffset.UTC));
        result.setResendAfter(LocalDateTime.ofInstant(createdAt.plusSeconds(60), ZoneOffset.UTC));
        return result;
    }
}
