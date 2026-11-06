package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.*;
import com.freecontent.community.user.ratelimit.ProfileUpdateRateLimiter;
import com.freecontent.community.user.service.UserProfileService;
import com.freecontent.community.user.service.transaction.ProfileUpdateTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class UserProfileServiceImpl implements UserProfileService {
    private final ProfileUpdateRateLimiter limiter;
    private final ProfileUpdateTransactionService transactions;

    public UserProfileServiceImpl(ProfileUpdateRateLimiter limiter, ProfileUpdateTransactionService transactions) {
        this.limiter = limiter;
        this.transactions = transactions;
    }

    @Override
    public CurrentUser update(UpdateProfileRequest request, SessionPrincipal currentSession, String sourceIp) {
        if (currentSession == null || currentSession.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        String userId = currentSession.user().id();
        limiter.acquire(userId, sourceIp);
        try {
            return transactions.update(request, userId, currentSession.authVersion());
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "资料修改数据服务暂不可用");
        }
    }
}
