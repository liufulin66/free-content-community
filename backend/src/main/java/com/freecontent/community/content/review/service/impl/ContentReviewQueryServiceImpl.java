package com.freecontent.community.content.review.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.ratelimit.ContentQueryRateLimiter;
import com.freecontent.community.content.review.service.ContentReviewQueryService;
import com.freecontent.community.content.review.service.transaction.ContentReviewQueryTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class ContentReviewQueryServiceImpl implements ContentReviewQueryService {
    private final ContentReviewQueryTransactionService transactions;
    private final ContentQueryRateLimiter limiter;
    public ContentReviewQueryServiceImpl(ContentReviewQueryTransactionService transactions, ContentQueryRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }

    @Override public ReviewResult getMyReview(String contentId, String reviewId, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        validateId(contentId, "contentId"); validateId(reviewId, "reviewId");
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.read(contentId, reviewId, principal); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核查询数据服务暂不可用"); }
    }

    private void validateId(String id, String field) {
        if (id == null || !id.matches("^[A-Za-z0-9_-]{1,64}$"))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "路径ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", field, "仅允许1到64位字母、数字、下划线及短横线")));
    }
}
