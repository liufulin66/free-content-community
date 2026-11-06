package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.user.dto.ChangePasswordRequest;
import com.freecontent.community.user.ratelimit.PasswordChangeRateLimiter;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.user.service.impl.PasswordChangeServiceImpl;
import com.freecontent.community.user.service.transaction.PasswordChangeTransactionService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordChangeServiceTest {
    private final PasswordChangeRateLimiter limiter = mock(PasswordChangeRateLimiter.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final UserAccountMapper users = mock(UserAccountMapper.class);
    private final PasswordChangeTransactionService transactions = mock(PasswordChangeTransactionService.class);
    private final RedisSessionStore sessions = mock(RedisSessionStore.class);
    private final PasswordChangeService service = new PasswordChangeServiceImpl(limiter, passwords, users, transactions, sessions);
    private final SessionPrincipal principal = new SessionPrincipal("session1",
            CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), TokenDigest.sha256("token"), 1L);
    private final ChangePasswordRequest request = new ChangePasswordRequest(" OldPassword! ", " NewPassword! ");

    @Test
    void wrongCurrentPasswordDoesNotChangePasswordOrRevokeSessions() {
        when(users.selectById("user1")).thenReturn(LoginTestSupport.account());
        assertError(ErrorCode.AUTHENTICATION_FAILED);
        verify(passwords).matches(" OldPassword! ", "stored-hash");
        verify(passwords, never()).encode(anyString());
        verifyNoInteractions(transactions, sessions);
    }

    @Test
    void credentialsAreVerifiedBeforeHashAndWriteAndCleanupRunsAfterWrite() {
        verified();
        when(transactions.change("user1", 1L, "stored-hash", "new-hash")).thenReturn(2L);
        service.change(request, principal, "ip");
        var order = inOrder(limiter, users, passwords, transactions, sessions);
        order.verify(limiter).acquire("user1", "ip");
        order.verify(users).selectById("user1");
        order.verify(passwords).matches(" OldPassword! ", "stored-hash");
        order.verify(passwords).encode(" NewPassword! ");
        order.verify(transactions).change("user1", 1L, "stored-hash", "new-hash");
        order.verify(sessions).revokeBeforeVersion("user1", 2L);
        assertFalse(request.toString().contains("OldPassword"));
        assertFalse(request.toString().contains("NewPassword"));
    }

    @Test
    void staleSessionVersionCannotVerifyOrChangePassword() {
        var account = LoginTestSupport.account();
        account.setAuthVersion(2L);
        when(users.selectById("user1")).thenReturn(account);
        assertError(ErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(passwords, transactions, sessions);
    }

    @Test
    void rateLimitRunsBeforePasswordWork() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire("user1", "ip");
        assertError(ErrorCode.RATE_LIMITED);
        verifyNoInteractions(users, passwords, transactions, sessions);
    }

    @Test
    void concurrentCredentialChangeRejectsWriteWithoutCleanup() {
        verified();
        when(transactions.change(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        assertError(ErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(sessions);
    }

    @Test
    void cleanupFailureAfterCommitKeepsSuccessfulResult() {
        verified();
        when(transactions.change(anyString(), anyLong(), anyString(), anyString())).thenReturn(2L);
        when(sessions.revokeBeforeVersion("user1", 2L)).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertDoesNotThrow(() -> service.change(request, principal, "ip"));
        verify(transactions, times(1)).change(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void databaseFailureIs503WithoutInternalDetails() {
        when(users.selectById("user1")).thenThrow(new DataAccessResourceFailureException("internal-sql"));
        var error = assertThrows(BusinessException.class, () -> service.change(request, principal, "ip"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
        assertFalse(error.getMessage().contains("internal-sql"));
        verifyNoInteractions(passwords, transactions, sessions);
    }

    private void verified() {
        when(users.selectById("user1")).thenReturn(LoginTestSupport.account());
        when(passwords.matches(anyString(), anyString())).thenReturn(true);
        when(passwords.encode(anyString())).thenReturn("new-hash");
    }

    private void assertError(ErrorCode expected) {
        assertEquals(expected, assertThrows(BusinessException.class, () -> service.change(request, principal, "ip")).errorCode());
    }
}
