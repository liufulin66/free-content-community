package com.freecontent.community.content.browse.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.ratelimit.PublicContentQueryRateLimiter;
import com.freecontent.community.content.browse.service.PublicContentListParameters;
import com.freecontent.community.content.browse.service.UserContentListService;
import com.freecontent.community.content.browse.service.transaction.PublicContentListTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class UserContentListServiceImpl implements UserContentListService {
    private final PublicContentCursor cursors;
    private final PublicContentQueryRateLimiter limiter;
    private final PublicContentListTransactionService transactions;

    public UserContentListServiceImpl(PublicContentCursor cursors, PublicContentQueryRateLimiter limiter,
            PublicContentListTransactionService transactions) {
        this.cursors = cursors;
        this.limiter = limiter;
        this.transactions = transactions;
    }

    @Override
    public ContentPageView listUserContents(String userId, String cursor, String limit, SessionPrincipal viewer, String sourceIp) {
        if (userId == null || !userId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "userId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        int size = PublicContentListParameters.pageSize(limit);
        PublicContentListParameters.requireCursorLength(cursor);
        String scope = viewer == null ? "anonymous" : "user:" + viewer.user().id();
        var query = cursors.openForAuthor(cursor, size, userId, scope);
        limiter.acquire(viewer == null ? null : viewer.user().id(), sourceIp);
        try {
            return transactions.list(query);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "作者公开内容列表数据服务暂不可用");
        }
    }

}
