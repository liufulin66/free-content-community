package com.freecontent.community.media.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.UploadPolicyProperties;
import com.freecontent.community.media.dto.*;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import com.freecontent.community.media.storage.UploadSigner;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 共享锁顺序为账号→上传会话；签名仅本地计算，整个事务不更新业务数据。 */
@Service
public class UploadQueryTransactionService {
    private final UserAccountMapper users;
    private final MediaUploadMapper uploads;
    private final MediaAssetMapper assets;
    private final UploadSigner signer;
    private final UploadPolicyProperties policy;
    private final Clock clock;

    public UploadQueryTransactionService(UserAccountMapper users, MediaUploadMapper uploads, MediaAssetMapper assets,
            UploadSigner signer, UploadPolicyProperties policy, Clock clock) {
        this.users = users;
        this.uploads = uploads;
        this.assets = assets;
        this.signer = signer;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public UploadSession read(String id, SessionPrincipal principal) {
        String owner = principal.user().id();
        UserAccount account = users.findByIdForShare(owner);
        if (account == null || account.getAuthVersion() == null || account.getAuthVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.getAccountStatus() == UserAccount.Status.DISABLED) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.getEmailVerified())) throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备上传查询权限");
        MediaUpload upload = uploads.findOwnedForShare(id, owner);
        if (upload == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        Instant now = clock.instant();
        Instant expiry = upload.getExpiresAt().toInstant(ZoneOffset.UTC);
        String state = upload.getState();
        if ("UPLOADING".equals(state) && !expiry.isAfter(now)) state = "EXPIRED";
        UploadTarget target = null;
        if ("UPLOADING".equals(state) && account.getAccountStatus() == UserAccount.Status.ACTIVE) {
            target = renewTarget(upload, owner, now, expiry);
        }
        return new UploadSession(upload.getId(), upload.getAssetId(), "SINGLE", state, upload.getVersion(),
                null, 1, List.of(), target, expiry);
    }

    private UploadTarget renewTarget(MediaUpload upload, String owner, Instant now, Instant expiry) {
        Instant signedAt = now.truncatedTo(ChronoUnit.SECONDS);
        long remaining = Duration.between(signedAt, expiry).getSeconds();
        int ttl = (int) Math.min(Math.min(policy.uploadUrlTtlSeconds(), 604800L), remaining);
        if (ttl <= 0) return null;
        MediaAsset asset = assets.selectById(upload.getAssetId());
        if (asset == null || !Objects.equals(asset.getOwnerId(), owner)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return signer.sign(signingView(upload, signedAt, ttl), asset.getDeclaredSizeBytes(), asset.getExpectedSha256());
    }

    private MediaUpload signingView(MediaUpload original, Instant signedAt, int ttl) {
        MediaUpload signing = new MediaUpload();
        signing.setId(original.getId());
        signing.setStorageKey(original.getStorageKey());
        signing.setContentType(original.getContentType());
        signing.setSignerId(original.getSignerId());
        signing.setSignatureAt(LocalDateTime.ofInstant(signedAt, ZoneOffset.UTC));
        signing.setSignatureTtlSeconds(ttl);
        return signing;
    }
}
