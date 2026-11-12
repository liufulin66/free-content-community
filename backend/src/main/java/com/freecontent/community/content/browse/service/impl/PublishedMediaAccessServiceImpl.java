package com.freecontent.community.content.browse.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.browse.service.PublishedMediaAccessService;
import com.freecontent.community.content.browse.ratelimit.PublishedMediaAccessRateLimiter;
import com.freecontent.community.content.browse.service.transaction.PublishedMediaAccessTransactionService;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class PublishedMediaAccessServiceImpl implements PublishedMediaAccessService {
    private final PublishedMediaAccessTransactionService transactions;
    private final MediaObjectReader objects;
    private final PublishedMediaAccessRateLimiter limiter;

    public PublishedMediaAccessServiceImpl(PublishedMediaAccessTransactionService transactions, MediaObjectReader objects,
            PublishedMediaAccessRateLimiter limiter) {
        this.transactions = transactions;
        this.objects = objects;
        this.limiter = limiter;
    }

    @Override
    public MediaAccess getAccess(String contentId, String assetId, SessionPrincipal viewer, String sourceIp) {
        validateId(contentId, "contentId");
        validateId(assetId, "assetId");
        limiter.acquire(viewer == null ? null : viewer.user().id(), sourceIp);
        try {
            var proof = transactions.prepare(contentId, assetId);
            objects.verify(proof.object());
            return transactions.finish(contentId, assetId, proof);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开媒体授权数据服务暂不可用");
        }
    }

    private void validateId(String id, String field) {
        if (id == null || !id.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "资源ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", field, "仅允许1到64位字母、数字、下划线及短横线")));
        }
    }
}
