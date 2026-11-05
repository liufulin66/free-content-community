package com.freecontent.community.auth.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.service.impl.LoginServiceImpl;
import com.freecontent.community.auth.dto.LoginRequest;
import com.freecontent.community.auth.ratelimit.LoginRateLimiter;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.auth.session.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginServiceTest {
    private final LoginRateLimiter limiter = mock(LoginRateLimiter.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final UserAccountMapper users = mock(UserAccountMapper.class);
    private final UserRoleMapper roles = mock(UserRoleMapper.class);
    private final RedisSessionStore sessions = mock(RedisSessionStore.class);
    private final LoginService service = new LoginServiceImpl(limiter, passwords, users, roles, sessions);
    private final LoginRequest request = new LoginRequest(" Creator@Example.test ", " Password! ", "浏览器");

    @Test
    void loginNormalizesEmailAndPreservesPasswordAndCreatesSession() {
        successfulPassword();
        when(roles.findRoles("user1")).thenReturn(List.of(UserRole.USER));
        Instant now = Instant.now();
        when(sessions.create("user1", 1, "浏览器")).thenReturn(new IssuedSession("token",
                new StoredSession("session1", "user1", 1, now, now.plusSeconds(86400), "浏览器")));
        var result = service.login(request, "192.0.2.1");
        assertEquals("Bearer", result.tokenType());
        assertEquals("token", result.accessToken());
        assertTrue(result.user().canCreate());
        assertEquals(now.plusSeconds(86400), result.expiresAt());
        verify(limiter).acquire("creator@example.test", "192.0.2.1");
        verify(passwords).matches(" Password! ", "stored-hash");
        assertFalse(request.toString().contains("Password!"));
        assertFalse(result.toString().contains("token"));
    }

    @Test
    void unknownEmailUsesDummyCheckAndSameFailureCode() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.login(request, "ip"));
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, ex.errorCode());
        verify(passwords).verifyMissingAccount(request.password());
        verifyNoInteractions(sessions, roles);
    }

    @Test
    void wrongPasswordDoesNotDiscloseDisabledStatus() {
        UserAccount account = LoginTestSupport.account();
        account.setAccountStatus(UserAccount.Status.DISABLED);
        when(users.findByEmail(anyString())).thenReturn(account);
        assertError(ErrorCode.AUTHENTICATION_FAILED);
        verify(users, never()).selectById(anyString());
        verifyNoInteractions(sessions, roles);
    }

    @Test
    void correctPasswordOfDisabledAccountCannotCreateSession() {
        UserAccount current = successfulPassword();
        current.setAccountStatus(UserAccount.Status.DISABLED);
        assertError(ErrorCode.ACCOUNT_RESTRICTED);
        verifyNoInteractions(sessions);
    }

    @Test
    void correctPasswordOfUnverifiedAccountCannotCreateSession() {
        successfulPassword().setEmailVerified(false);
        assertError(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        verifyNoInteractions(sessions);
    }

    @Test
    void writeBlockedAccountMayLoginWithCanCreateFalse() {
        successfulPassword().setAccountStatus(UserAccount.Status.WRITE_BLOCKED);
        when(roles.findRoles("user1")).thenReturn(List.of(UserRole.USER));
        Instant now = Instant.now();
        when(sessions.create(anyString(), anyLong(), any())).thenReturn(new IssuedSession("token",
                new StoredSession("session1", "user1", 1, now, now.plusSeconds(86400), null)));
        assertFalse(service.login(request, "ip").user().canCreate());
    }

    @Test
    void passwordChangeDuringVerificationPreventsIssuance() {
        successfulPassword().setAuthVersion(2L);
        assertError(ErrorCode.AUTHENTICATION_FAILED);
        verifyNoInteractions(sessions);
    }

    @Test
    void changedHashCannotIssueSessionEvenIfVersionWasNotBumped() {
        successfulPassword().setPasswordHash("new-hash");
        assertError(ErrorCode.AUTHENTICATION_FAILED);
        verifyNoInteractions(sessions);
    }

    @Test
    void rateLimitRunsBeforeDatabaseAndExpensivePasswordWork() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(anyString(), anyString());
        assertError(ErrorCode.RATE_LIMITED);
        verifyNoInteractions(users, passwords, roles, sessions);
    }

    @Test
    void databaseFailureIs503WithoutDetails() {
        when(users.findByEmail(anyString())).thenThrow(new DataAccessResourceFailureException("internal-db"));
        var ex = assertThrows(BusinessException.class, () -> service.login(request, "ip"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, ex.errorCode());
        assertFalse(ex.getMessage().contains("internal-db"));
    }

    private UserAccount successfulPassword() {
        when(users.findByEmail(anyString())).thenReturn(LoginTestSupport.account());
        when(passwords.matches(anyString(), anyString())).thenReturn(true);
        UserAccount current = LoginTestSupport.account();
        when(users.selectById("user1")).thenReturn(current);
        return current;
    }

    private void assertError(ErrorCode code) {
        assertEquals(code, assertThrows(BusinessException.class, () -> service.login(request, "ip")).errorCode());
    }
}
