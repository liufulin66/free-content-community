package com.freecontent.community.content.review.service.impl;

import com.freecontent.community.content.review.dto.ReviewDecisionRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.service.ReviewDecisionService;
import com.freecontent.community.content.review.service.transaction.ReviewDecisionTransactionService;
import com.freecontent.community.content.review.ratelimit.ReviewDecisionRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ReviewDecisionServiceImpl implements ReviewDecisionService {
    private static final Logger log = LoggerFactory.getLogger(ReviewDecisionServiceImpl.class);
    private final ReviewDecisionTransactionService transactions;
    private final ReviewDecisionRateLimiter limiter;
    private final JsonMapper json;
    public ReviewDecisionServiceImpl(ReviewDecisionTransactionService transactions, ReviewDecisionRateLimiter limiter, JsonMapper json) {
        this.transactions = transactions; this.limiter = limiter; this.json = json;
    }
    @Override public ReviewResult decide(String reviewId, ReviewDecisionRequest input, String key, SessionPrincipal principal, String sourceIp, String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (reviewId == null || !reviewId.matches("^[A-Za-z0-9_-]{1,64}$"))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "审核ID格式不正确", null, null, List.of(new ProblemDetailResponse.Violation("path", "reviewId", "仅允许1到64位字母、数字、下划线及短横线")));
        if (key == null || key.codePointCount(0, key.length()) < 16 || key.codePointCount(0, key.length()) > 128)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键长度必须为16到128个字符", null, null, List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "长度必须为16到128个字符")));
        ReviewDecisionTransactionService.Claim claim = null;
        try {
            String fingerprint = TokenDigest.sha256(json.writeValueAsString(List.of(reviewId, input.getExpectedContentVersion(), input.getExpectedReviewVersion(), input.getDecision().name(), input.getReason())));
            claim = transactions.claim(reviewId, input, TokenDigest.sha256(key), fingerprint, principal);
            if (claim.replay() != null) return claim.replay();
            limiter.acquire(principal.user().id(), sourceIp);
            return transactions.finish(claim, principal, requestId);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try { transactions.release(claim); }
                catch (RuntimeException cleanup) { log.warn("Review decision reservation cleanup pending, reservationId={}", claim.request().getId()); }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException)
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核决定数据服务暂不可用");
            throw ex;
        }
    }
}
