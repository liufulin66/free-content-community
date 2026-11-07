package com.freecontent.community.media.service.impl;

import com.freecontent.community.auth.security.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.*;
import com.freecontent.community.media.ratelimit.UploadCompletionRateLimiter;
import com.freecontent.community.media.service.UploadCompletionService;
import com.freecontent.community.media.service.transaction.UploadCompletionTransactionService;
import com.freecontent.community.media.storage.UploadObjectInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.*;

@Service
public class UploadCompletionServiceImpl implements UploadCompletionService {
    private static final Logger log = LoggerFactory.getLogger(UploadCompletionServiceImpl.class);
    private final UploadCompletionTransactionService transactions;
    private final UploadObjectInspector inspector;
    private final UploadCompletionRateLimiter limiter;

    public UploadCompletionServiceImpl(UploadCompletionTransactionService transactions, UploadObjectInspector inspector,
                                      UploadCompletionRateLimiter limiter) {
        this.transactions = transactions; this.inspector = inspector; this.limiter = limiter;
    }

    @Override
    public TaskReceipt complete(String uploadId, CompleteUploadRequest request, String key, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (uploadId == null || !uploadId.matches("^[A-Za-z0-9_-]{1,64}$")) throw validation("path", "uploadId", "上传ID格式不正确");
        if (key == null || key.codePointCount(0, key.length()) < 16 || key.codePointCount(0, key.length()) > 128) {
            throw validation("header", "Idempotency-Key", "幂等键长度必须为16到128个字符");
        }
        if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 1) {
            throw validation("body", "expectedVersion", "版本必须为正整数");
        }
        if (request.hasParts()) throw validation("body", "parts", "SINGLE上传必须省略parts，分片上传尚未开放");
        // 完成申请的重放也计数，与创建接口的配额和重放规则相互独立。
        limiter.acquire(principal.user().id(), sourceIp);
        UploadCompletionTransactionService.Claim claim = null;
        try {
            String fingerprint = TokenDigest.sha256("completeMyUpload\n" + uploadId + "\n" + request.getExpectedVersion());
            claim = transactions.claim(uploadId, request.getExpectedVersion(), TokenDigest.sha256(key), fingerprint, principal);
            if (claim.replay() != null) return claim.replay();
            var object = inspector.inspect(claim.upload().getStorageKey());
            if (object.sizeBytes() != claim.asset().getDeclaredSizeBytes()) {
                throw new BusinessException(ErrorCode.UPLOAD_STATE_CONFLICT, "存储对象大小与上传声明不符，请重新上传", claim.upload().getVersion());
            }
            if (!Objects.equals(object.contentType(), claim.asset().getDeclaredContentType())) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "存储对象Content-Type与上传声明不符");
            }
            if (object.etag() == null || object.etag().isBlank() || object.etag().length() > 200 || object.lastModified() == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "存储对象快照暂不可用");
            }
            return transactions.finish(claim, request.getExpectedVersion(), principal, object);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try { transactions.release(claim); }
                catch (RuntimeException cleanup) { log.warn("Upload completion reservation cleanup pending, reservationId={}", claim.request().getId()); }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "上传完成数据服务暂不可用");
            }
            throw ex;
        }
    }

    private BusinessException validation(String location, String path, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null, null,
                List.of(new ProblemDetailResponse.Violation(location, path, message)));
    }
}
