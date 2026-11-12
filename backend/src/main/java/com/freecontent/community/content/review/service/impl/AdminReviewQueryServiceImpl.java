package com.freecontent.community.content.review.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.dto.ReviewPage;
import com.freecontent.community.content.review.dto.AdminReviewDetail;
import com.freecontent.community.content.review.ratelimit.AdminReviewQueryRateLimiter;
import com.freecontent.community.content.review.service.AdminReviewQueryService;
import com.freecontent.community.content.review.service.transaction.AdminReviewQueryTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

@Service
public class AdminReviewQueryServiceImpl implements AdminReviewQueryService {
    private static final Set<String> STATES = Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED", "INVALIDATED");
    private static final Set<String> PURPOSES = Set.of("INITIAL", "UPDATE", "REMEDIATION");
    private final AdminReviewQueryTransactionService transactions;
    private final AdminReviewQueryRateLimiter limiter;
    public AdminReviewQueryServiceImpl(AdminReviewQueryTransactionService transactions, AdminReviewQueryRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }

    @Override public ReviewPage list(String page, String pageSize, String state, String purpose, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        BigInteger number = pagination(page, "page", 1, null);
        int size = pagination(pageSize, "pageSize", 20, 100).intValueExact();
        String status = enumValue(state, "state", STATES, "PENDING"), intent = enumValue(purpose, "purpose", PURPOSES, null);
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.list(number, size, status, intent, principal); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "后台审核查询数据服务暂不可用"); }
    }

    @Override public AdminReviewDetail get(String reviewId, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (reviewId == null || !reviewId.matches("^[A-Za-z0-9_-]{1,64}$"))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "审核ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "reviewId", "仅允许1到64位字母、数字、下划线及短横线")));
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.read(reviewId, principal); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "后台审核详情数据服务暂不可用"); }
    }

    private BigInteger pagination(String raw, String field, int defaultValue, Integer maximum) {
        if (raw == null) return BigInteger.valueOf(defaultValue);
        if (!raw.matches("-?[0-9]+")) throw invalid(ErrorCode.MALFORMED_REQUEST, field, "必须为整数");
        BigInteger value = new BigInteger(raw);
        if (value.signum() <= 0 || (maximum != null && value.compareTo(BigInteger.valueOf(maximum)) > 0))
            throw invalid(ErrorCode.VALIDATION_ERROR, field, maximum == null ? "必须大于等于1" : "必须在1到" + maximum + "之间");
        return value;
    }
    private String enumValue(String raw, String field, Set<String> allowed, String defaultValue) {
        if (raw == null) return defaultValue;
        if (!allowed.contains(raw)) throw invalid(ErrorCode.MALFORMED_REQUEST, field, "不支持的枚举值");
        return raw;
    }
    private BusinessException invalid(ErrorCode code, String field, String reason) {
        return new BusinessException(code, "查询参数不合法", null, null, List.of(ProblemDetailResponse.Violation.query(field, reason)));
    }
}
