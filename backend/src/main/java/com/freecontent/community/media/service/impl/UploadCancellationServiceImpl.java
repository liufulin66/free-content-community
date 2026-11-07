package com.freecontent.community.media.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.service.UploadCancellationService;
import com.freecontent.community.media.service.transaction.UploadCancellationTransactionService;
import com.freecontent.community.media.ratelimit.UploadCancellationRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class UploadCancellationServiceImpl implements UploadCancellationService {
    private final UploadCancellationTransactionService transactions;
    private final UploadCancellationRateLimiter limiter;
    public UploadCancellationServiceImpl(UploadCancellationTransactionService transactions, UploadCancellationRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }

    @Override
    public void cancel(String id, Integer version, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (id == null || !id.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "上传会话ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "uploadId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        if (version == null || version < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "版本必须为正整数", null, null,
                    List.of(new ProblemDetailResponse.Violation("query", "expectedVersion", "必须为正整数")));
        }
        limiter.acquire(principal.user().id(), sourceIp);
        try { transactions.cancel(id, version, principal); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "上传取消数据服务暂不可用"); }
    }
}
