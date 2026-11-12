package com.freecontent.community.content.review.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.ratelimit.AdminReviewMediaPreviewRateLimiter;
import com.freecontent.community.content.review.service.AdminReviewMediaPreviewService;
import com.freecontent.community.content.review.service.transaction.AdminReviewMediaPreviewTransactionService;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class AdminReviewMediaPreviewServiceImpl implements AdminReviewMediaPreviewService {
    private final AdminReviewMediaPreviewTransactionService transactions;
    private final MediaObjectReader objects;
    private final AdminReviewMediaPreviewRateLimiter limiter;
    public AdminReviewMediaPreviewServiceImpl(AdminReviewMediaPreviewTransactionService transactions, MediaObjectReader objects, AdminReviewMediaPreviewRateLimiter limiter) {
        this.transactions = transactions; this.objects = objects; this.limiter = limiter;
    }
    @Override public MediaAccess getAccess(String reviewId, String assetId, SessionPrincipal principal, String sourceIp, String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        validateId(reviewId, "reviewId"); validateId(assetId, "assetId");
        limiter.acquire(principal.user().id(), sourceIp);
        try {
            var proof = transactions.prepare(reviewId, assetId, principal);
            objects.verify(proof.object());
            return transactions.finish(reviewId, assetId, principal, proof, requestId);
        } catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核媒体预览数据服务暂不可用"); }
    }
    private void validateId(String id, String field) {
        if (id == null || !id.matches("^[A-Za-z0-9_-]{1,64}$")) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "资源ID格式不正确", null, null,
                List.of(new ProblemDetailResponse.Violation("path", field, "仅允许1到64位字母、数字、下划线及短横线")));
    }
}
