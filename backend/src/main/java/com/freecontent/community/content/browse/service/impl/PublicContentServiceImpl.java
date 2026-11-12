package com.freecontent.community.content.browse.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.PublicContentView;
import com.freecontent.community.content.browse.ratelimit.PublicContentQueryRateLimiter;
import com.freecontent.community.content.browse.service.PublicContentService;
import com.freecontent.community.content.browse.service.transaction.PublicContentTransactionService;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class PublicContentServiceImpl implements PublicContentService {
    private final PublicContentTransactionService transactions;
    private final PublicContentQueryRateLimiter limiter;

    public PublicContentServiceImpl(PublicContentTransactionService transactions, PublicContentQueryRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public PublicContentView getPublishedContent(String contentId, SessionPrincipal viewer, String sourceIp) {
        if (contentId == null || !contentId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "内容ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "contentId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(viewer == null ? null : viewer.user().id(), sourceIp);
        try {
            return transactions.read(contentId);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开内容查询数据服务暂不可用");
        }
    }
}
