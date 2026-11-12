package com.freecontent.community.content.browse.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.ratelimit.PublicContentQueryRateLimiter;
import com.freecontent.community.content.browse.service.PublicContentListService;
import com.freecontent.community.content.browse.service.PublicContentListParameters;
import com.freecontent.community.content.browse.service.transaction.PublicContentListTransactionService;
import com.freecontent.community.content.model.ContentKind;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class PublicContentListServiceImpl implements PublicContentListService {
    private final PublicContentCursor cursors;
    private final PublicContentQueryRateLimiter limiter;
    private final PublicContentListTransactionService transactions;
    public PublicContentListServiceImpl(PublicContentCursor cursors, PublicContentQueryRateLimiter limiter, PublicContentListTransactionService transactions) {
        this.cursors = cursors; this.limiter = limiter; this.transactions = transactions;
    }
    @Override
    public ContentPageView listPublishedContents(String cursor, String limit, String kind, String tagId, SessionPrincipal viewer, String sourceIp) {
        int size = PublicContentListParameters.pageSize(limit);
        ContentKind type = null;
        if (kind != null) {
            try { type = ContentKind.valueOf(kind); }
            catch (IllegalArgumentException ex) { throw PublicContentListParameters.invalidQuery("kind", "仅允许ARTICLE或VIDEO"); }
        }
        if (tagId != null && !tagId.matches("^[A-Za-z0-9_-]{1,64}$")) throw PublicContentListParameters.invalidQuery("tagId", "标签ID格式不正确");
        PublicContentListParameters.requireCursorLength(cursor);
        String actor = viewer == null ? null : viewer.user().id();
        var query = cursors.open(cursor, size, type, tagId, actor == null ? "anonymous" : "user:" + actor);
        limiter.acquire(actor, sourceIp);
        try { return transactions.list(query); }
        catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开内容列表数据服务暂不可用");
        }
    }
}
