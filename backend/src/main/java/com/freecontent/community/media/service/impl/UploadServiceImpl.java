package com.freecontent.community.media.service.impl;

import com.freecontent.community.auth.security.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.*;
import com.freecontent.community.media.dto.*;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.service.UploadService;
import com.freecontent.community.media.service.transaction.UploadTransactionService;
import com.freecontent.community.media.storage.UploadSigner;
import com.freecontent.community.media.ratelimit.UploadCreationRateLimiter;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class UploadServiceImpl implements UploadService {
    private static final Logger log = LoggerFactory.getLogger(UploadServiceImpl.class);
    private final UploadPolicyProperties policy;
    private final UploadCreationProperties creation;
    private final UploadTransactionService transactions;
    private final UploadSigner signer;
    private final Clock clock;
    private final JsonMapper json;
    private final UploadCreationRateLimiter limiter;

    public UploadServiceImpl(UploadPolicyProperties policy, UploadCreationProperties creation,
            UploadTransactionService transactions, UploadSigner signer, Clock clock, JsonMapper json, UploadCreationRateLimiter limiter) {
        this.policy = policy;
        this.creation = creation;
        this.transactions = transactions;
        this.signer = signer;
        this.clock = clock;
        this.json = json;
        this.limiter = limiter;
    }

    @Override
    public UploadSession create(CreateUploadRequest request, String key, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (key == null || key.codePointCount(0, key.length()) < 16 || key.codePointCount(0, key.length()) > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "幂等键长度必须为16到128个字符", null, null,
                    List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "长度必须为16到128个字符")));
        }
        UploadTransactionService.Claim claim = null;
        try {
            claim = transactions.claim(principal, TokenDigest.sha256(key), TokenDigest.sha256(json.writeValueAsString(request)));
            // 重放必须返回已提交结果；只有真正的新请求才消耗限流额度。
            if (claim.replay() != null) {
                MediaAsset replayAsset = claim.replayAsset();
                return response(claim.replay(), signer.sign(claim.replay(),
                        replayAsset.getDeclaredSizeBytes(), replayAsset.getExpectedSha256()));
            }
            limiter.acquire(principal.user().id(), sourceIp);
            validateNewFile(request);
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            MediaAsset asset = newAsset(request, principal.user().id(), now);
            MediaUpload upload = newUpload(asset, now);
            UploadTarget target = signer.sign(upload, asset.getDeclaredSizeBytes(), asset.getExpectedSha256());
            transactions.complete(claim, principal, asset, upload);
            return response(upload, target);
        } catch (RuntimeException ex) {
            if (claim != null && claim.replay() == null) {
                try { transactions.release(claim); }
                catch (RuntimeException cleanup) { log.warn("Upload reservation cleanup pending, reservationId={}", claim.request().getId()); }
            }
            if (ex instanceof DataAccessException || ex instanceof TransactionException) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "上传数据服务暂不可用");
            }
            throw ex;
        }
    }

    private void validateNewFile(CreateUploadRequest request) {
        if (request.getScene() == CreateUploadRequest.Scene.CONTENT_VIDEO || !policy.allowedImageMimeTypes().contains(request.getContentType())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "当前仅支持策略允许的图片单文件上传");
        }
        if (request.getSizeBytes() > policy.maxImageBytes()) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "图片大小超过当前上传限制");
        if (request.getSizeBytes() >= policy.multipartThresholdBytes()) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "分片上传能力尚未开放");
    }

    private MediaAsset newAsset(CreateUploadRequest request, String owner, Instant now) {
        MediaAsset asset = new MediaAsset();
        asset.setId(id());
        asset.setOwnerId(owner);
        asset.setScene(request.getScene().name());
        asset.setFileName(request.getFileName());
        asset.setDeclaredContentType(request.getContentType());
        asset.setDeclaredSizeBytes(request.getSizeBytes());
        asset.setExpectedSha256(request.getSha256());
        asset.setStorageKey("private/" + owner + "/" + asset.getId() + "/source");
        asset.setState("WAITING_UPLOAD");
        asset.setCreatedAt(utc(now));
        return asset;
    }

    private MediaUpload newUpload(MediaAsset asset, Instant now) {
        MediaUpload upload = new MediaUpload();
        upload.setId(id());
        upload.setAssetId(asset.getId());
        upload.setOwnerId(asset.getOwnerId());
        upload.setStorageKey(asset.getStorageKey());
        upload.setContentType(asset.getDeclaredContentType());
        upload.setState("UPLOADING");
        upload.setVersion(1);
        upload.setCreatedAt(utc(now));
        upload.setSignatureAt(utc(now));
        upload.setSignatureTtlSeconds(Math.min(policy.uploadUrlTtlSeconds(), creation.sessionTtlSeconds()));
        upload.setSignerId(signer.identity());
        upload.setExpiresAt(utc(now.plusSeconds(creation.sessionTtlSeconds())));
        return upload;
    }

    private UploadSession response(MediaUpload upload, UploadTarget target) {
        return new UploadSession(upload.getId(), upload.getAssetId(), "SINGLE", "UPLOADING", 1, null, 1, List.of(), target, upload.getExpiresAt().toInstant(ZoneOffset.UTC));
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
