package com.freecontent.community.content.workspace.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.CreateContentRequest;
import com.freecontent.community.content.workspace.service.ContentCreationService;
import com.freecontent.community.content.workspace.service.transaction.ContentCreationTransactionService;
import com.freecontent.community.content.validation.ContentDraftValidator;
import com.freecontent.community.common.error.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.freecontent.community.content.workspace.ratelimit.ContentCreationRateLimiter;
import org.springframework.stereotype.Service;

@Service
public class ContentCreationServiceImpl implements ContentCreationService {
    private static final Logger log = LoggerFactory.getLogger(ContentCreationServiceImpl.class);
    private final ContentCreationTransactionService transactions;
    private final ContentDraftValidator validator;
    private final JsonMapper json;
    private final ContentCreationRateLimiter limiter;
    public ContentCreationServiceImpl(ContentCreationTransactionService transactions, ContentDraftValidator validator, JsonMapper json, ContentCreationRateLimiter limiter) {
        this.transactions = transactions; this.validator = validator; this.json = json; this.limiter = limiter;
    }

    @Override
    public ContentWorkspace create(CreateContentRequest request, String key, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (key == null || key.codePointCount(0, key.length()) < 16 || key.codePointCount(0, key.length()) > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键长度必须为16到128个字符", null, null,
                    List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "长度必须为16到128个字符")));
        }
        validator.validate(request.toDraftData());
        ContentCreationTransactionService.Claim claim = null;
        try {
            claim = transactions.claim(request, TokenDigest.sha256(key), TokenDigest.sha256(json.writeValueAsString(request)), principal);
            if (claim.replay() != null) return claim.replay();
            limiter.acquire(principal.user().id(), sourceIp);
            return transactions.finish(claim, principal);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try { transactions.release(claim); }
                catch (RuntimeException cleanup) { log.warn("Content creation reservation cleanup pending, reservationId={}", claim.request().getId()); }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容创建数据服务暂不可用");
            throw ex;
        }
    }
}
