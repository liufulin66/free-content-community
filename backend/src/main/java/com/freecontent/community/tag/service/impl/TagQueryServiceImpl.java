package com.freecontent.community.tag.service.impl;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.dto.TagPageView;
import com.freecontent.community.tag.dto.TagListView;
import com.freecontent.community.tag.ratelimit.TagQueryRateLimiter;
import com.freecontent.community.tag.ratelimit.ActiveTagQueryRateLimiter;
import com.freecontent.community.tag.service.TagQueryService;
import com.freecontent.community.tag.service.transaction.TagQueryTransactionService;

@Service
public class TagQueryServiceImpl implements TagQueryService {
    private final TagQueryTransactionService transactions;
    private final TagQueryRateLimiter limiter;
    private final ActiveTagQueryRateLimiter activeLimiter;

    public TagQueryServiceImpl(TagQueryTransactionService transactions, TagQueryRateLimiter limiter, ActiveTagQueryRateLimiter activeLimiter) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.activeLimiter = activeLimiter;
    }

    @Override
    public TagListView listActiveTags(String sourceIp, Set<String> queryParameters) {
        activeLimiter.acquire(sourceIp);
        if (!queryParameters.isEmpty()) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "有效标签查询不接受查询参数", null, null,
                    queryParameters.stream().map(name -> ProblemDetailResponse.Violation.query(name, "未定义的查询参数")).toList());
        }
        try {
            return transactions.activeTags();
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "有效标签查询数据服务暂不可用");
        }
    }

    @Override
    public TagPageView listAdminTags(String page, String pageSize, SessionPrincipal principal, String sourceIp) {
        requirePrincipal(principal);
        BigInteger number = paginationValue(page, "page", 1, null);
        int size = paginationValue(pageSize, "pageSize", 20, 100).intValueExact();
        limiter.acquire(principal.user().id(), sourceIp);
        try {
            return transactions.list(number, size, principal);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "标签查询数据服务暂不可用");
        }
    }

    private BigInteger paginationValue(String raw, String field, int defaultValue, Integer maximum) {
        if (raw == null) return BigInteger.valueOf(defaultValue);
        if (!raw.matches("-?[0-9]+")) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "分页参数必须是整数", null, null,
                    List.of(ProblemDetailResponse.Violation.query(field, "必须是整数")));
        }
        BigInteger value = new BigInteger(raw);
        if (value.signum() <= 0 || (maximum != null && value.compareTo(BigInteger.valueOf(maximum)) > 0)) {
            String message = maximum == null ? "必须大于等于1" : "必须在1到" + maximum + "之间";
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数超出允许范围", null, null,
                    List.of(ProblemDetailResponse.Violation.query(field, message)));
        }
        return value;
    }

    private void requirePrincipal(SessionPrincipal principal) {
        if (principal == null || principal.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    @Override
    public TagView getAdminTag(String tagId, SessionPrincipal principal, String sourceIp) {
        requirePrincipal(principal);
        if (tagId == null || !tagId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "标签ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "tagId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(principal.user().id(), sourceIp);
        try {
            return transactions.read(tagId, principal);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "标签查询数据服务暂不可用");
        }
    }
}
