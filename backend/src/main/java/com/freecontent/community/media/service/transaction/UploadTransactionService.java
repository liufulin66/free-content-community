package com.freecontent.community.media.service.transaction;

import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.time.*;

@Service
public class UploadTransactionService {
    private final AccountAccessService accounts;
    private final MediaAssetMapper assets;
    private final MediaUploadMapper uploads;
    private final UploadRequestMapper requests;
    private final Clock clock;

    public UploadTransactionService(AccountAccessService accounts, MediaAssetMapper assets, MediaUploadMapper uploads, UploadRequestMapper requests, Clock clock) {
        this.accounts = accounts; this.assets=assets; this.uploads=uploads; this.requests=requests; this.clock=clock;
    }

    public record Claim(UploadRequestRecord request, MediaUpload replay, MediaAsset replayAsset) { }

    @Transactional
    public Claim claim(SessionPrincipal principal, String keyHash, String fingerprint) {
        String owner = authorize(principal).id();
        LocalDateTime now = now();
        UploadRequestRecord record = requests.lockByKey(owner, keyHash);
        if (record != null && !record.getExpiresAt().isAfter(now)) {
            requests.deleteById(record.getId());
            record = null;
        }
        if (record != null) {
            if (!Objects.equals(record.getFingerprint(), fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCEEDED".equals(record.getState())) {
                MediaUpload previous = uploads.selectById(record.getUploadId());
                if (previous == null || !Objects.equals(previous.getOwnerId(), owner)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                if (!"UPLOADING".equals(previous.getState()) || !previous.getExpiresAt().isAfter(now)) {
                    throw new BusinessException(ErrorCode.PERMISSION_DENIED, "原上传会话已不可写入，不能重放上传凭证");
                }
                MediaAsset previousAsset = assets.selectById(previous.getAssetId());
                if (previousAsset == null || !Objects.equals(previousAsset.getOwnerId(), owner)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                return new Claim(record, previous, previousAsset);
            }
            if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now)) {
                throw inProgress(Math.max(1, (Duration.between(now, record.getLeaseUntil()).toMillis() + 999) / 1000));
            }
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            requests.renew(record.getId(), record.getLeaseToken(), record.getLeaseUntil());
        } else {
            record = new UploadRequestRecord();
            record.setId(id()); record.setOwnerId(owner); record.setKeyHash(keyHash); record.setFingerprint(fingerprint);
            record.setState("PROCESSING"); record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            record.setCreatedAt(now); record.setExpiresAt(now.plusHours(24));
            requests.insert(record);
        }
        return new Claim(record, null, null);
    }

    @Transactional
    public void complete(Claim claim, SessionPrincipal principal, MediaAsset asset, MediaUpload upload) {
        String owner = authorize(principal).id();
        UploadRequestRecord current = requests.lockByKey(owner, claim.request().getKeyHash());
        if (current == null || !Objects.equals(current.getId(), claim.request().getId())
                || !"PROCESSING".equals(current.getState()) || !Objects.equals(current.getLeaseToken(), claim.request().getLeaseToken())
                || current.getLeaseUntil() == null || !current.getLeaseUntil().isAfter(now())) throw inProgress(1);
        assets.insert(asset);
        uploads.insert(upload);
        if (requests.complete(current.getId(), current.getLeaseToken(), upload.getId()) != 1) throw inProgress(1);
    }

    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(), claim.request().getLeaseToken()); }

    private AccountSnapshot authorize(SessionPrincipal principal) {
        AccountSnapshot user = accounts.findByIdForUpdate(principal.user().id());
        if (user == null || user.authVersion() == null || user.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (!user.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(user.emailVerified())) throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        return user;
    }

    private BusinessException inProgress(long seconds) {
        return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同上传请求正在处理，请稍后重试", null, seconds, List.of());
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
