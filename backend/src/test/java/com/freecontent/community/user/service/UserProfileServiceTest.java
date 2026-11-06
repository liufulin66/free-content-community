package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.*;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.ratelimit.ProfileUpdateRateLimiter;
import com.freecontent.community.user.service.impl.UserProfileServiceImpl;
import com.freecontent.community.user.service.transaction.ProfileUpdateTransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserProfileServiceTest {
    private final ProfileUpdateRateLimiter limiter = mock(ProfileUpdateRateLimiter.class);
    private final ProfileUpdateTransactionService transactions = mock(ProfileUpdateTransactionService.class);
    private final UserProfileService service = new UserProfileServiceImpl(limiter, transactions);
    private final SessionPrincipal actor = new SessionPrincipal("session1", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)),
            TokenDigest.sha256("token"), 1);
    private final UpdateProfileRequest patch = JsonMapper.builder().build()
            .convertValue(Map.of("expectedVersion", 1, "bio", "简介"), UpdateProfileRequest.class);

    @Test
    void rateLimitStopsDatabaseWork() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire("user1", "ip");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> service.update(patch, actor, "ip")).errorCode());
        verifyNoInteractions(transactions);
    }

    @Test
    void databaseFailureIs503WithoutLeakingSqlDetails() {
        when(transactions.update(any(), anyString(), anyLong())).thenThrow(new DataAccessResourceFailureException("internal-sql-detail"));
        BusinessException error = assertThrows(BusinessException.class, () -> service.update(patch, actor, "ip"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
        assertFalse(error.getMessage().contains("internal-sql-detail"));
    }
}
