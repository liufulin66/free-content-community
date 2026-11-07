package com.freecontent.community.media.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.UploadCleanupProperties;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

/** 账号→会话→素材固定锁序，取消状态、素材终态及清理任务共享一次提交。 */
@Service
public class UploadCancellationTransactionService {
    private final UserAccountMapper users;
    private final MediaUploadMapper uploads;
    private final MediaAssetMapper assets;
    private final UploadCleanupTaskMapper tasks;
    private final UploadCleanupProperties properties;
    private final Clock clock;

    public UploadCancellationTransactionService(UserAccountMapper users, MediaUploadMapper uploads, MediaAssetMapper assets,
            UploadCleanupTaskMapper tasks, UploadCleanupProperties properties, Clock clock) {
        this.users = users;
        this.uploads = uploads;
        this.assets = assets;
        this.tasks = tasks;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void cancel(String id, int expectedVersion, SessionPrincipal principal) {
        String owner = principal.user().id();
        UserAccount account = users.findByIdForUpdate(owner);
        if (account == null || account.getAuthVersion() == null || account.getAuthVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (account.getAccountStatus() != UserAccount.Status.ACTIVE) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.getEmailVerified())) throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        MediaUpload upload = uploads.findOwnedForUpdate(id, owner);
        if (upload == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if ("CANCELLED".equals(upload.getState())) return;
        if (!"UPLOADING".equals(upload.getState()) || expectedVersion != upload.getVersion()
                || upload.getVersion() == Integer.MAX_VALUE) throw conflict(upload.getVersion());
        MediaAsset asset = assets.findOwnedForUpdate(upload.getAssetId(), owner);
        if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!"WAITING_UPLOAD".equals(asset.getState())) throw conflict(upload.getVersion());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!upload.getExpiresAt().isAfter(now)) throw conflict(upload.getVersion());
        if (uploads.cancel(id, owner, expectedVersion, now) != 1 || assets.failCancelledUpload(asset.getId(), owner) != 1) throw conflict(upload.getVersion());
        tasks.insert(cleanupTask(upload, asset, owner, now));
    }

    private UploadCleanupTask cleanupTask(MediaUpload upload, MediaAsset asset, String owner, LocalDateTime now) {
        UploadCleanupTask task = new UploadCleanupTask();
        task.setId(UUID.randomUUID().toString().replace("-", ""));
        task.setOwnerId(owner); task.setUploadId(upload.getId()); task.setAssetId(asset.getId());
        task.setStorageKey(upload.getStorageKey()); task.setSignerId(upload.getSignerId());
        task.setState("PENDING"); task.setAttemptCount(0);
        task.setAvailableAt(upload.getExpiresAt().plusSeconds(properties.graceSeconds()));
        task.setCreatedAt(now); task.setUpdatedAt(now);
        return task;
    }

    private BusinessException conflict(int version) { return new BusinessException(ErrorCode.UPLOAD_STATE_CONFLICT, "上传会话状态或版本已变化，请重新查询", version); }
}
