package com.freecontent.community.content.workspace.service.impl;

import com.freecontent.community.content.workspace.dto.EditDraftRequest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.workspace.service.ContentDraftSaveService;
import com.freecontent.community.content.workspace.service.transaction.ContentDraftSaveTransactionService;
import com.freecontent.community.content.workspace.ratelimit.ContentDraftSaveRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ContentDraftSaveServiceImpl implements ContentDraftSaveService {
    private final ContentDraftSaveTransactionService transactions;
    private final ContentDraftSaveRateLimiter limiter;
    public ContentDraftSaveServiceImpl(ContentDraftSaveTransactionService transactions,ContentDraftSaveRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }
    @Override public ContentWorkspace save(String contentId,EditDraftRequest patch,SessionPrincipal principal,String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        validatePatch(contentId,patch);
        try {
            BusinessException rejection = transactions.preflight(contentId,patch,principal);
            limiter.acquire(principal.user().id(),sourceIp);
            if (rejection != null) throw rejection;
            return transactions.save(contentId,patch,principal);
        }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"草稿保存数据服务暂不可用"); }
    }

    private void validatePatch(String contentId,EditDraftRequest patch) {
        if (contentId == null || !contentId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,"内容ID格式不正确",null,null,
                    List.of(new ProblemDetailResponse.Violation("path","contentId","仅允许1到64位字母、数字、下划线及短横线")));
        }
        var violations = new ArrayList<ProblemDetailResponse.Violation>();
        if (!patch.hasMutableField()) violations.add(ProblemDetailResponse.Violation.body("","至少提供一个待修改字段"));
        if (patch.hasTitle() && patch.getTitle() == null) violations.add(ProblemDetailResponse.Violation.body("title","标题不能为null"));
        if (patch.hasBodyMarkdown() && patch.getBodyMarkdown() == null) violations.add(ProblemDetailResponse.Violation.body("bodyMarkdown","正文不能为null"));
        if (patch.hasImageAssetIds() && patch.getImageAssetIds() == null) violations.add(ProblemDetailResponse.Violation.body("imageAssetIds","图片数组不能为null"));
        if (patch.hasTagIds() && patch.getTagIds() == null) violations.add(ProblemDetailResponse.Violation.body("tagIds","标签数组不能为null"));
        if (!violations.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"草稿修改字段不合法",null,null,violations);
    }
}
