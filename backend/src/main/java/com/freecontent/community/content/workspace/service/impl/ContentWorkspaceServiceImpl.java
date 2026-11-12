package com.freecontent.community.content.workspace.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.WorkspacePage;
import com.freecontent.community.content.workspace.service.ContentWorkspaceService;
import com.freecontent.community.content.workspace.service.transaction.ContentWorkspaceTransactionService;
import com.freecontent.community.content.ratelimit.ContentQueryRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;
import java.math.BigInteger;

@Service
public class ContentWorkspaceServiceImpl implements ContentWorkspaceService {
    private static final Set<String> LIFECYCLES = Set.of("ACTIVE","DELETED");
    private static final Set<String> PUBLICATION_STATUSES = Set.of("UNPUBLISHED","PUBLISHED","WITHDRAWN","TAKEN_DOWN");
    private static final Set<String> KINDS = Set.of("ARTICLE","VIDEO");
    private final ContentWorkspaceTransactionService transactions;
    private final ContentQueryRateLimiter limiter;
    public ContentWorkspaceServiceImpl(ContentWorkspaceTransactionService transactions, ContentQueryRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }

    @Override public ContentWorkspace getMyWorkspace(String contentId, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (contentId == null || !contentId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "内容ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "contentId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.read(contentId, principal); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容工作区数据服务暂不可用"); }
    }

    @Override public WorkspacePage listMyContents(String page,String pageSize,String lifecycle,String publicationStatus,String kind,
            SessionPrincipal principal,String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        BigInteger number = paginationValue(page,"page",1,null);
        int size = paginationValue(pageSize,"pageSize",20,100).intValueExact();
        String state = enumValue(lifecycle,"lifecycle",LIFECYCLES,"ACTIVE");
        String publication = enumValue(publicationStatus,"publicationStatus",PUBLICATION_STATUSES,null);
        String type = enumValue(kind,"kind",KINDS,null);
        limiter.acquire(principal.user().id(),sourceIp);
        try { return transactions.list(number,size,state,publication,type,principal); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"内容列表数据服务暂不可用"); }
    }

    private BigInteger paginationValue(String raw,String field,int defaultValue,Integer maximum) {
        if (raw == null) return BigInteger.valueOf(defaultValue);
        if (!raw.matches("-?[0-9]+")) throw queryError(ErrorCode.MALFORMED_REQUEST,field,"必须为整数");
        BigInteger value = new BigInteger(raw);
        if (value.signum() <= 0 || (maximum != null && value.compareTo(BigInteger.valueOf(maximum)) > 0)) {
            throw queryError(ErrorCode.VALIDATION_ERROR,field,maximum == null ? "必须大于等于1" : "必须在1到"+maximum+"之间");
        }
        return value;
    }
    private String enumValue(String raw,String field,Set<String> allowed,String defaultValue) {
        if (raw == null) return defaultValue;
        if (!allowed.contains(raw)) throw queryError(ErrorCode.MALFORMED_REQUEST,field,"不支持的枚举值");
        return raw;
    }
    private BusinessException queryError(ErrorCode code,String field,String reason) {
        return new BusinessException(code,"查询参数不合法",null,null,List.of(ProblemDetailResponse.Violation.query(field,reason)));
    }
}
