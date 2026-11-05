package com.freecontent.community.auth.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionAuthenticationServiceTest {
    private final RedisSessionStore sessions = mock(RedisSessionStore.class);
    private final UserAccountMapper users = mock(UserAccountMapper.class);
    private final UserRoleMapper roles = mock(UserRoleMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T08:00:00Z"), ZoneOffset.UTC);
    private final SessionAuthenticationService service = new SessionAuthenticationService(sessions, users, roles, clock);

    @Test
    void rolesAndWriteRestrictionsUseCurrentDatabaseState() {
        UserAccount account = fixture();
        when(roles.findRoles("user1")).thenReturn(List.of(UserRole.USER), List.of(UserRole.USER, UserRole.ADMIN));
        assertEquals(List.of(UserRole.USER), service.authenticate("token").user().roles());
        assertTrue(service.authenticate("token").user().roles().contains(UserRole.ADMIN));
        account.setAccountStatus(UserAccount.Status.WRITE_BLOCKED);
        assertFalse(service.authenticate("token").user().canCreate());
    }

    @Test
    void oldAuthVersionIsRejected() {
        fixture().setAuthVersion(2L);
        assertError(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    void disabledAccountIsRejected() {
        fixture().setAccountStatus(UserAccount.Status.DISABLED);
        assertError(ErrorCode.ACCOUNT_RESTRICTED);
    }

    @Test
    void unverifiedAccountIsRejected() {
        fixture().setEmailVerified(false);
        assertError(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    }

    @Test
    void unknownSessionDoesNotQueryDatabase() {
        assertError(ErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(users, roles);
    }

    @Test
    void expiredSessionDoesNotQueryDatabase() {
        when(sessions.find("token")).thenReturn(new StoredSession("session1", "user1", 1,
                clock.instant().minusSeconds(86400), clock.instant(), null));
        assertError(ErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(users, roles);
    }

    @Test
    void dependencyFailureIsNotReportedAsInvalidCredential() {
        when(sessions.find("token")).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertError(ErrorCode.DEPENDENCY_UNAVAILABLE);
        verifyNoInteractions(users, roles);
    }

    @Test
    void databaseFailureFailsClosedWith503() {
        fixture();
        when(users.selectById("user1")).thenThrow(new DataAccessResourceFailureException("database detail"));
        assertError(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    private UserAccount fixture() {
        when(sessions.find("token")).thenReturn(new StoredSession("session1", "user1", 1,
                clock.instant(), clock.instant().plusSeconds(86400), null));
        UserAccount account = LoginTestSupport.account();
        when(users.selectById("user1")).thenReturn(account);
        when(roles.findRoles("user1")).thenReturn(List.of(UserRole.USER));
        return account;
    }

    private void assertError(ErrorCode code) {
        assertEquals(code, assertThrows(BusinessException.class, () -> service.authenticate("token")).errorCode());
    }
}
