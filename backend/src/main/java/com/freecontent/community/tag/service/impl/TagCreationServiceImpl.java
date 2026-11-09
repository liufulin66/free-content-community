package com.freecontent.community.tag.service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.dto.TagWriteRequest;
import com.freecontent.community.tag.ratelimit.TagCreationRateLimiter;
import com.freecontent.community.tag.service.TagCreationService;
import com.freecontent.community.tag.service.transaction.TagCreationTransactionService;

import tools.jackson.databind.json.JsonMapper;

@Service
public class TagCreationServiceImpl implements TagCreationService {

    private static final Logger log = LoggerFactory.getLogger(TagCreationServiceImpl.class);
    private final TagCreationTransactionService transactions;
    private final TagCreationRateLimiter limiter;
    private final JsonMapper json;

    public TagCreationServiceImpl(TagCreationTransactionService transactions, TagCreationRateLimiter limiter, JsonMapper json) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public TagView create(TagWriteRequest request, String key, SessionPrincipal principal, String sourceIp) {
        //对输入进行校验
        if (principal == null || principal.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (key == null || key.codePointCount(0, key.length()) < 16 || key.codePointCount(0, key.length()) > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键长度必须为16到128个字符", null, null,
                    List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "长度必须为16到128个字符")));
        }
        //构造幂等输入和指纹
        var input = new TagCreationTransactionService.Input(request.getName(), request.getDescription(), request.getStatus());
        TagCreationTransactionService.Claim claim = null;
        try {
            claim = transactions.claim(principal, TokenDigest.sha256(key), TokenDigest.sha256(json.writeValueAsString(input)), input);
            if (claim.replay() != null) {
                return claim.replay();
            }
            limiter.acquire(principal.user().id(), sourceIp);
            return transactions.finish(claim, principal);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try {
                    transactions.release(claim);
                } catch (RuntimeException cleanup) {
                    log.warn("Tag creation reservation cleanup pending, reservationId={}", claim.request().getId());
                }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "标签创建数据服务暂不可用");
            }
            throw ex;
        }
    }
}
