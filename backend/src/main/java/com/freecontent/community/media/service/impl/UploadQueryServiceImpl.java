package com.freecontent.community.media.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.UploadSession;
import com.freecontent.community.media.service.UploadQueryService;
import com.freecontent.community.media.service.transaction.UploadQueryTransactionService;
import com.freecontent.community.media.ratelimit.UploadQueryRateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class UploadQueryServiceImpl implements UploadQueryService {
    private final UploadQueryTransactionService queries;
    private final UploadQueryRateLimiter limiter;

    public UploadQueryServiceImpl(UploadQueryTransactionService queries, UploadQueryRateLimiter limiter) {
        this.queries = queries;
        this.limiter = limiter;
    }

    @Override
    public UploadSession getMyUpload(String uploadId, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (uploadId == null || !uploadId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "上传会话ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "uploadId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(principal.user().id(), sourceIp);
        try { return queries.read(uploadId, principal); }
        catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "上传查询数据服务暂不可用");
        }
    }
}
