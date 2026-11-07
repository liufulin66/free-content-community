package com.freecontent.community.media.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.MediaAssetView;
import com.freecontent.community.media.ratelimit.MediaAssetQueryRateLimiter;
import com.freecontent.community.media.service.MediaAssetQueryService;
import com.freecontent.community.media.service.transaction.MediaAssetQueryTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class MediaAssetQueryServiceImpl implements MediaAssetQueryService {
    private final MediaAssetQueryTransactionService transactions;
    private final MediaAssetQueryRateLimiter limiter;
    public MediaAssetQueryServiceImpl(MediaAssetQueryTransactionService transactions, MediaAssetQueryRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }
    @Override
    public MediaAssetView getMyAsset(String assetId, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (assetId == null || !assetId.matches("^[A-Za-z0-9_-]{1,64}$")) throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "素材ID格式不正确", null, null, List.of(new ProblemDetailResponse.Violation("path", "assetId", "仅允许1到64位字母、数字、下划线及短横线")));
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.read(assetId, principal); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "素材查询数据服务暂不可用"); }
    }
}
