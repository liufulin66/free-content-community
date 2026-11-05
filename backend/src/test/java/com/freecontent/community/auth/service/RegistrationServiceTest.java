package com.freecontent.community.auth.service;

import com.freecontent.community.auth.ratelimit.RegistrationRateLimiter;
import com.freecontent.community.auth.service.transaction.RegistrationTransactionService;
import com.freecontent.community.auth.service.transaction.RegistrationVerificationService;
import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationServiceTest {
    private final RegistrationRateLimiter limiter = mock(RegistrationRateLimiter.class);
    private final RegistrationVerificationService verification = mock(RegistrationVerificationService.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final RegistrationTransactionService transactions = mock(RegistrationTransactionService.class);
    private final RegistrationService service = new RegistrationService(limiter, verification, hasher, transactions);
    private final RegisterRequest request = new RegisterRequest(" Creator@Example.test ",
            " RegistrationPassword! ", "challenge1", "123456", "创作者");

    @Test
    void invalidCodeDoesNotPerformExpensiveHash() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.register(request, "127.0.0.1"));
        assertEquals(ErrorCode.CHALLENGE_INVALID, error.errorCode());
        verify(limiter).acquire("creator@example.test", "127.0.0.1");
        verifyNoInteractions(hasher, transactions);
    }

    @Test
    void codeInvalidatedDuringHashCannotCreateAccount() {
        when(verification.verify(request, "creator@example.test")).thenReturn(true);
        when(hasher.encode(request.password())).thenReturn("hash");
        when(transactions.register(request, "creator@example.test", "hash"))
                .thenReturn(new RegistrationTransactionService.Result(RegistrationTransactionService.Status.INVALID_CHALLENGE, null));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.register(request, "127.0.0.1"));
        assertEquals(ErrorCode.CHALLENGE_INVALID, error.errorCode());
        verify(hasher).encode(" RegistrationPassword! ");
    }

    @Test
    void uniqueEmailRaceBecomesConflictWithoutLeakingDatabaseError() {
        when(verification.verify(any(), anyString())).thenReturn(true);
        when(hasher.encode(anyString())).thenReturn("hash");
        when(transactions.register(any(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("database detail"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.register(request, "127.0.0.1"));
        assertEquals(ErrorCode.REGISTRATION_NOT_ALLOWED, error.errorCode());
        assertFalse(error.getMessage().contains("database detail"));
    }
}
