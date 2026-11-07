package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.ratelimit.UploadQueryRateLimiter;
import com.freecontent.community.media.service.impl.UploadQueryServiceImpl;
import com.freecontent.community.media.service.transaction.UploadQueryTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadQueryServiceTest {
    @Test
    void unavailableTransactionIsReportedAsDependencyUnavailable() {
        var queries = mock(UploadQueryTransactionService.class);
        var limiter = mock(UploadQueryRateLimiter.class);
        var principal = principal();
        when(queries.read("upload_001", principal)).thenThrow(new CannotCreateTransactionException("simulated"));
        var service = new UploadQueryServiceImpl(queries, limiter);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.getMyUpload("upload_001", principal, "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
    }

    @Test
    void rejectedQuotaDoesNotReadOrSignTheUpload() {
        var queries = mock(UploadQueryTransactionService.class);
        var limiter = mock(UploadQueryRateLimiter.class);
        var service = new UploadQueryServiceImpl(queries, limiter);
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire("user1", "source");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class,
                () -> service.getMyUpload("upload_001", principal(), "source")).errorCode());
        verifyNoInteractions(queries);
    }

    private SessionPrincipal principal() {
        return new SessionPrincipal("session1", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
    }
}
