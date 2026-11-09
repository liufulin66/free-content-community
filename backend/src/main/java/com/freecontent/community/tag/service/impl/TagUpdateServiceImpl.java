package com.freecontent.community.tag.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.tag.dto.TagUpdateRequest;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.service.TagUpdateService;
import com.freecontent.community.tag.service.transaction.TagUpdateTransactionService;
import com.freecontent.community.tag.ratelimit.TagUpdateRateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import java.util.ArrayList;
import java.util.List;

@Service
public class TagUpdateServiceImpl implements TagUpdateService {
    private final TagUpdateTransactionService transactions;
    private final TagUpdateRateLimiter limiter;

    public TagUpdateServiceImpl(TagUpdateTransactionService transactions, TagUpdateRateLimiter limiter) {
        this.transactions = transactions;
        this.limiter = limiter;
    }

    @Override
    public TagView update(String tagId, TagUpdateRequest patch, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        validatePatch(tagId, patch);
        limiter.acquire(principal.user().id(), sourceIp);
        try {
            return transactions.update(tagId, patch, principal);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "标签修改数据服务暂不可用");
        }
    }

    private void validatePatch(String tagId, TagUpdateRequest patch) {
        if (tagId == null || !tagId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "标签ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "tagId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        List<ProblemDetailResponse.Violation> violations = new ArrayList<>();
        if (!patch.hasMutableField()) violations.add(ProblemDetailResponse.Violation.body("", "至少提供一个待修改字段"));
        if (patch.hasName() && patch.getName() == null) violations.add(ProblemDetailResponse.Violation.body("name", "名称不能为null"));
        if (patch.hasDescription() && patch.getDescription() == null) violations.add(ProblemDetailResponse.Violation.body("description", "说明不能为null，清空请提交空字符串"));
        if (patch.hasStatus() && patch.getStatus() == null) violations.add(ProblemDetailResponse.Violation.body("status", "状态不能为null"));
        if (!violations.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "标签修改字段不合法", null, null, violations);
    }
}
