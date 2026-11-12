package com.freecontent.community.user.service;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.service.impl.PublicUserServiceImpl;
import com.freecontent.community.user.ratelimit.PublicUserQueryRateLimiter;
import com.freecontent.community.user.service.transaction.PublicUserTransactionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicUserServiceTest {
    private final PublicUserTransactionService transactions = mock(PublicUserTransactionService.class);
    private final PublicUserQueryRateLimiter limiter = mock(PublicUserQueryRateLimiter.class);
    private final PublicUserService service = new PublicUserServiceImpl(transactions, limiter);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "bad.id", "a/b", "用户", "a" + "1234567890123456789012345678901234567890123456789012345678901234"})
    void invalidUserIdIs422BeforeDatabaseAccess(String userId) {
        var error = assertThrows(BusinessException.class, () -> service.getPublicUser(userId, null, "source"));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertEquals("path", error.violations().get(0).location());
        assertEquals("userId", error.violations().get(0).path());
        verifyNoInteractions(transactions, limiter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseOrTransactionFailureBecomes503WithoutInternalDetails(boolean transactionFailure) {
        RuntimeException failure = transactionFailure
                ? new CannotCreateTransactionException("sensitive connection detail")
                : new DataAccessResourceFailureException("sensitive SQL detail");
        when(transactions.read("author_1")).thenThrow(failure);
        var error = assertThrows(BusinessException.class, () -> service.getPublicUser("author_1", null, "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
        assertFalse(error.getMessage().contains("sensitive"));
    }
}
