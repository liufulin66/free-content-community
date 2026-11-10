package com.freecontent.community.content.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.service.ContentMediaPreviewService;
import com.freecontent.community.content.service.transaction.ContentMediaPreviewTransactionService;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.content.ratelimit.ContentMediaPreviewRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class ContentMediaPreviewServiceImpl implements ContentMediaPreviewService {
    private final ContentMediaPreviewTransactionService transactions;
    private final MediaObjectReader objects;
    private final ContentMediaPreviewRateLimiter limiter;
    public ContentMediaPreviewServiceImpl(ContentMediaPreviewTransactionService transactions,MediaObjectReader objects,ContentMediaPreviewRateLimiter limiter) {
        this.transactions = transactions; this.objects = objects; this.limiter = limiter;
    }
    @Override public MediaAccess getAccess(String content,String revision,String asset,SessionPrincipal principal,String sourceIp,String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        validateId(content,"contentId"); validateId(revision,"revisionId"); validateId(asset,"assetId");
        limiter.acquire(principal.user().id(),sourceIp);
        try {
            var reference = transactions.prepare(content,revision,asset,principal);
            objects.verify(reference);
            return transactions.finish(content,revision,asset,principal,reference,requestId);
        }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"媒体预览数据服务暂不可用"); }
    }
    private void validateId(String id,String field) {
        if (id == null || !id.matches("^[A-Za-z0-9_-]{1,64}$")) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"资源ID格式不正确",null,null,
                List.of(new ProblemDetailResponse.Violation("path",field,"仅允许1到64位字母、数字、下划线及短横线")));
    }
}
