package com.freecontent.community.media.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.TaskReceipt;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import com.freecontent.community.media.storage.UploadObjectInspector.StoredObject;
import com.freecontent.community.media.storage.UploadSigner;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 账号→幂等记录→会话→素材；存储网络检查在claim与finish两个短事务之间。 */
@Service
public class UploadCompletionTransactionService {
    private final UserAccountMapper users;
    private final MediaUploadMapper uploads;
    private final MediaAssetMapper assets;
    private final UploadCompletionRequestMapper requests;
    private final MediaProcessingTaskMapper tasks;
    private final UploadSigner signer;
    private final Clock clock;

    public UploadCompletionTransactionService(UserAccountMapper users, MediaUploadMapper uploads, MediaAssetMapper assets,
            UploadCompletionRequestMapper requests, MediaProcessingTaskMapper tasks, UploadSigner signer, Clock clock) {
        this.users = users; this.uploads = uploads; this.assets = assets;
        this.requests = requests; this.tasks = tasks; this.signer = signer; this.clock = clock;
    }

    public record Claim(UploadCompletionRequestRecord request, MediaUpload upload, MediaAsset asset, TaskReceipt replay) { }

    @Transactional
    public Claim claim(String uploadId, int version, String keyHash, String fingerprint, SessionPrincipal principal) {
        String owner = authorize(principal);
        MediaUpload visible = uploads.selectById(uploadId);
        if (visible == null || !Objects.equals(owner, visible.getOwnerId())) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        LocalDateTime now = now();
        UploadCompletionRequestRecord record = requests.lockByKey(owner, keyHash);
        if (record != null && !record.getExpiresAt().isAfter(now)) {
            requests.deleteById(record.getId()); record = null;
        }
        if (record != null) {
            if (!Objects.equals(fingerprint, record.getFingerprint())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCEEDED".equals(record.getState())) {
                MediaProcessingTask task = tasks.findOwned(record.getTaskId(), owner);
                MediaAsset asset = assets.selectById(visible.getAssetId());
                if (task == null || asset == null || !Objects.equals(asset.getOwnerId(), owner)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                if (!Objects.equals(task.getUploadId(), uploadId) || !Objects.equals(task.getAssetId(), asset.getId())) throw unavailable();
                return new Claim(record, null, null, TaskReceipt.pending(task.getId(), task.getAssetId()));
            }
            if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now)) {
                throw inProgress(Math.max(1, (Duration.between(now, record.getLeaseUntil()).toMillis() + 999) / 1000));
            }
        }
        MediaUpload upload = uploads.findOwnedForUpdate(uploadId, owner);
        MediaAsset asset = checkedAsset(upload, owner, version, now);
        if (record == null) {
            record = new UploadCompletionRequestRecord();
            record.setId(id()); record.setOwnerId(owner); record.setKeyHash(keyHash); record.setFingerprint(fingerprint);
            record.setUploadId(uploadId); record.setState("PROCESSING"); record.setCreatedAt(now); record.setExpiresAt(now.plusHours(24));
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            requests.insert(record);
        } else {
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            if (requests.renew(record.getId(), record.getLeaseToken(), record.getLeaseUntil()) != 1) throw inProgress(1);
        }
        return new Claim(record, upload, asset, null);
    }

    @Transactional
    public TaskReceipt finish(Claim claim, int version, SessionPrincipal principal, StoredObject object) {
        String owner = authorize(principal);
        LocalDateTime now = now();
        UploadCompletionRequestRecord record = requests.lockByKey(owner, claim.request().getKeyHash());
        if (record == null || !Objects.equals(record.getId(), claim.request().getId()) || !"PROCESSING".equals(record.getState())
                || !Objects.equals(record.getLeaseToken(), claim.request().getLeaseToken())
                || record.getLeaseUntil() == null || !record.getLeaseUntil().isAfter(now)) throw inProgress(1);
        MediaUpload upload = uploads.findOwnedForUpdate(claim.upload().getId(), owner);
        MediaAsset asset = checkedAsset(upload, owner, version, now);
        if (!sameSource(claim, upload, asset)) throw conflict(upload.getVersion());
        MediaProcessingTask task = task(upload, asset, object, now);
        if (uploads.completeUpload(upload.getId(), owner, version, now) != 1
                || assets.startProcessing(asset.getId(), owner) != 1) throw conflict(upload.getVersion());
        tasks.insert(task);
        if (requests.complete(record.getId(), record.getLeaseToken(), task.getId()) != 1) throw inProgress(1);
        return TaskReceipt.pending(task.getId(), task.getAssetId());
    }

    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(), claim.request().getLeaseToken()); }

    private MediaAsset checkedAsset(MediaUpload upload, String owner, int version, LocalDateTime now) {
        if (upload == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!"UPLOADING".equals(upload.getState()) || upload.getVersion() != version
                || version == Integer.MAX_VALUE || !upload.getExpiresAt().isAfter(now)) throw conflict(upload.getVersion());
        MediaAsset asset = assets.findOwnedForUpdate(upload.getAssetId(), owner);
        if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!"WAITING_UPLOAD".equals(asset.getState())) throw conflict(upload.getVersion());
        if (!Objects.equals(upload.getStorageKey(), asset.getStorageKey())
                || !Objects.equals(upload.getStorageKey(), "private/" + owner + "/" + asset.getId() + "/source")
                || !Objects.equals(upload.getContentType(), asset.getDeclaredContentType())
                || !Objects.equals(upload.getSignerId(), signer.identity())) throw unavailable();
        return asset;
    }

    private boolean sameSource(Claim claim, MediaUpload upload, MediaAsset asset) {
        return Objects.equals(claim.upload().getAssetId(), upload.getAssetId())
                && Objects.equals(claim.upload().getStorageKey(), upload.getStorageKey())
                && Objects.equals(claim.upload().getSignerId(), upload.getSignerId())
                && Objects.equals(claim.asset().getDeclaredContentType(), asset.getDeclaredContentType())
                && Objects.equals(claim.asset().getDeclaredSizeBytes(), asset.getDeclaredSizeBytes())
                && Objects.equals(claim.asset().getExpectedSha256(), asset.getExpectedSha256())
                && Objects.equals(claim.asset().getScene(), asset.getScene());
    }

    private MediaProcessingTask task(MediaUpload upload, MediaAsset asset, StoredObject object, LocalDateTime now) {
        var task = new MediaProcessingTask();
        task.setId(id()); task.setOwnerId(upload.getOwnerId()); task.setUploadId(upload.getId()); task.setAssetId(asset.getId());
        task.setType("MEDIA_PROCESSING"); task.setState("PENDING"); task.setProgressPercent(0); task.setRetryable(false);
        task.setStorageKey(upload.getStorageKey()); task.setSignerId(upload.getSignerId());
        task.setObjectEtag(object.etag()); task.setObjectVersionId(object.versionId()); task.setObjectSizeBytes(object.sizeBytes());
        task.setObjectContentType(object.contentType()); task.setObjectLastModified(object.lastModified());
        task.setExpectedSha256(asset.getExpectedSha256()); task.setCreatedAt(now); task.setUpdatedAt(now);
        return task;
    }

    private String authorize(SessionPrincipal principal) {
        UserAccount user = users.findByIdForUpdate(principal.user().id());
        if (user == null || user.getAuthVersion() == null || user.getAuthVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (user.getAccountStatus() != UserAccount.Status.ACTIVE) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(user.getEmailVerified())) throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备上传完成权限");
        return user.getId();
    }

    private BusinessException conflict(int version) { return new BusinessException(ErrorCode.UPLOAD_STATE_CONFLICT, "上传状态、版本或存储结果不满足完成条件，请重新查询", version); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "上传存储状态暂不可用"); }
    private BusinessException inProgress(long seconds) { return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同完成请求正在处理中", null, seconds, List.of()); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
