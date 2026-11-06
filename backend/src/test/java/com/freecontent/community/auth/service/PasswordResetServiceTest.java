package com.freecontent.community.auth.service;

import com.freecontent.community.auth.AuthTestSupport;
import com.freecontent.community.auth.dto.ResetPasswordRequest;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.ratelimit.PasswordResetRateLimiter;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.auth.service.impl.PasswordResetServiceImpl;
import com.freecontent.community.auth.service.transaction.EmailChallengeVerificationService;
import com.freecontent.community.auth.service.transaction.PasswordResetTransactionService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {
    private final PasswordResetRateLimiter limiter = mock(PasswordResetRateLimiter.class);
    private final EmailChallengeVerificationService verification = mock(EmailChallengeVerificationService.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final PasswordResetTransactionService transactions = mock(PasswordResetTransactionService.class);
    private final RedisSessionStore sessions = mock(RedisSessionStore.class);
    private final PasswordResetService service = new PasswordResetServiceImpl(limiter, verification, passwords,
            transactions, sessions, AuthTestSupport.passwordResetProperties());
    private final ResetPasswordRequest request = new ResetPasswordRequest(" Creator@Example.test ",
            "challenge1", "000123", " NewPassword! ");

    @Test
    void invalidCodeDoesNotComputePasswordOrChangeAccount() {
        assertError(ErrorCode.CHALLENGE_INVALID);
        verify(limiter).acquire("creator@example.test", "ip");
        verify(verification).verify("challenge1", "creator@example.test", EmailChallengePurpose.PASSWORD_RESET, "000123", 5);
        verifyNoInteractions(passwords, transactions, sessions);
    }

    @Test
    void normalizesEmailPreservesPasswordAndCleansOnlyAfterTransactionReturns() {
        verified();
        when(transactions.reset(request, "creator@example.test", "hash"))
                .thenReturn(new PasswordResetTransactionService.Result(PasswordResetTransactionService.Status.RESET, "user1", 2L));
        service.reset(request, "ip");
        var order = inOrder(limiter, verification, passwords, transactions, sessions);
        order.verify(limiter).acquire("creator@example.test", "ip");
        order.verify(verification).verify("challenge1", "creator@example.test", EmailChallengePurpose.PASSWORD_RESET, "000123", 5);
        order.verify(passwords).encode(" NewPassword! ");
        order.verify(transactions).reset(request, "creator@example.test", "hash");
        order.verify(sessions).revokeBeforeVersion("user1", 2L);
        assertFalse(request.toString().contains("NewPassword"));
        assertFalse(request.toString().contains("000123"));
    }

    @Test
    void challengeInvalidatedDuringHashCannotChangePasswordOrClearSessions() {
        verified();
        when(transactions.reset(any(), anyString(), anyString())).thenReturn(
                new PasswordResetTransactionService.Result(PasswordResetTransactionService.Status.INVALID_CHALLENGE, null, null));
        assertError(ErrorCode.CHALLENGE_INVALID);
        verifyNoInteractions(sessions);
    }

    @Test
    void correctChallengeForUnknownAccountCompletesWithoutSessionCleanup() {
        verified();
        when(transactions.reset(any(), anyString(), anyString())).thenReturn(
                new PasswordResetTransactionService.Result(PasswordResetTransactionService.Status.NO_ACCOUNT, null, null));
        assertDoesNotThrow(() -> service.reset(request, "ip"));
        verifyNoInteractions(sessions);
    }

    @Test
    void cleanupFailureAfterCommitDoesNotReportPasswordResetFailureOrRepeatTransaction() {
        verified();
        when(transactions.reset(any(), anyString(), anyString())).thenReturn(
                new PasswordResetTransactionService.Result(PasswordResetTransactionService.Status.RESET, "user1", 2L));
        when(sessions.revokeBeforeVersion("user1", 2L)).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertDoesNotThrow(() -> service.reset(request, "ip"));
        verify(transactions, times(1)).reset(any(), anyString(), anyString());
    }

    @Test
    void limiterStopsWorkBeforeVerificationAndHashing() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(anyString(), anyString());
        assertError(ErrorCode.RATE_LIMITED);
        verifyNoInteractions(verification, passwords, transactions, sessions);
    }

    @Test
    void databaseFailureIs503WithoutInternalDetails() {
        when(verification.verify(anyString(), anyString(), any(), anyString(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("internal-sql-detail"));
        var ex = assertThrows(BusinessException.class, () -> service.reset(request, "ip"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, ex.errorCode());
        assertFalse(ex.getMessage().contains("internal-sql-detail"));
        verifyNoInteractions(passwords, transactions, sessions);
    }

    @Test
    void hashCapacityFailureDoesNotConsumeChallengeOrClearSessions() {
        verified();
        when(passwords.encode(anyString())).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertError(ErrorCode.DEPENDENCY_UNAVAILABLE);
        verifyNoInteractions(transactions, sessions);
    }

    private void verified() {
        when(verification.verify(anyString(), anyString(), any(), anyString(), anyInt())).thenReturn(true);
        when(passwords.encode(anyString())).thenReturn("hash");
    }

    private void assertError(ErrorCode expected) {
        assertEquals(expected, assertThrows(BusinessException.class, () -> service.reset(request, "ip")).errorCode());
    }
}
