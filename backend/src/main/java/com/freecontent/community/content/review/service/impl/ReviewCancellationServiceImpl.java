package com.freecontent.community.content.review.service.impl;

import com.freecontent.community.content.review.dto.CancelReviewRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.ratelimit.ReviewCancellationRateLimiter;
import com.freecontent.community.content.review.service.ReviewCancellationService;
import com.freecontent.community.content.review.service.transaction.ReviewCancellationTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class ReviewCancellationServiceImpl implements ReviewCancellationService {
    private final ReviewCancellationTransactionService transactions;
    private final ReviewCancellationRateLimiter limiter;
    public ReviewCancellationServiceImpl(ReviewCancellationTransactionService transactions, ReviewCancellationRateLimiter limiter) { this.transactions = transactions; this.limiter = limiter; }
    @Override public ReviewResult cancel(String contentId, String reviewId, CancelReviewRequest input, SessionPrincipal principal, String sourceIp, String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        validateId(contentId, "contentId"); validateId(reviewId, "reviewId");
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.cancel(contentId, reviewId, input, principal, requestId); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核撤销数据服务暂不可用"); }
    }
    private void validateId(String id, String field) {
        if (id == null || !id.matches("^[A-Za-z0-9_-]{1,64}$")) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "路径ID格式不正确", null, null,
                List.of(new ProblemDetailResponse.Violation("path", field, "仅允许1到64位字母、数字、下划线及短横线")));
    }
}
