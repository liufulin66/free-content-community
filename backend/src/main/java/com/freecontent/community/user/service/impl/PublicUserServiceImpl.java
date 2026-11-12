package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.user.dto.PublicUserView;
import com.freecontent.community.user.ratelimit.PublicUserQueryRateLimiter;
import com.freecontent.community.user.service.PublicUserService;
import com.freecontent.community.user.service.transaction.PublicUserTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class PublicUserServiceImpl implements PublicUserService {
    private final PublicUserTransactionService transactions;
    private final PublicUserQueryRateLimiter limiter;

    public PublicUserServiceImpl(PublicUserTransactionService transactions, PublicUserQueryRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public PublicUserView getPublicUser(String userId, SessionPrincipal viewer, String sourceIp) {
        if (userId == null || !userId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "userId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(viewer == null ? null : viewer.user().id(), sourceIp);
        try {
            return transactions.read(userId);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开个人主页数据服务暂不可用");
        }
    }
}
