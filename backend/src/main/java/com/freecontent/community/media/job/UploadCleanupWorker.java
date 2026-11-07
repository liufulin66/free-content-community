package com.freecontent.community.media.job;

import com.freecontent.community.media.config.UploadCleanupProperties;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import com.freecontent.community.media.storage.*;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** 短租约认领、事务外删除、带令牌回写；重复删除同一已取消对象是幂等的。 */
@Component
public class UploadCleanupWorker {
    private static final Logger log = LoggerFactory.getLogger(UploadCleanupWorker.class);
    private final UploadCleanupTaskMapper tasks;
    private final MediaUploadMapper uploads;
    private final MediaAssetMapper assets;
    private final UploadObjectCleaner cleaner;
    private final UploadSigner signer;
    private final UploadCleanupProperties properties;
    private final Clock clock;

    public UploadCleanupWorker(UploadCleanupTaskMapper tasks, MediaUploadMapper uploads, MediaAssetMapper assets,
            UploadObjectCleaner cleaner, UploadSigner signer, UploadCleanupProperties properties, Clock clock) {
        this.tasks=tasks; this.uploads=uploads; this.assets=assets; this.cleaner=cleaner;
        this.signer=signer; this.properties=properties; this.clock=clock;
    }

    @Scheduled(fixedDelayString = "${app.media.upload-cleanup.poll-delay-millis:1000}")
    public void poll() {
        if (!properties.enabled()) return;
        try {
            // 清理不紧急，每轮一个任务，避免存储超时长时间占用共享调度线程。
            for (String id : tasks.findDue(now(), 1)) {
                try { processTask(id); }
                catch (DataAccessException ex) { log.warn("Upload cleanup database operation unavailable, taskId={}", id); }
            }
        } catch (DataAccessException ex) { log.warn("Upload cleanup scan unavailable"); }
    }

    void processTask(String id) {
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime started = now();
        if (tasks.claim(id, token, started, started.plusSeconds(properties.leaseSeconds())) != 1) return;
        UploadCleanupTask task = tasks.selectById(id);
        if (task == null || !"RUNNING".equals(task.getState()) || !token.equals(task.getLeaseToken())) return;
        if (task.getAttemptCount() > properties.maxAttempts()) {
            tasks.finish(id, token, "FAILED", "ATTEMPTS_EXHAUSTED", now()); return;
        }
        if (!Objects.equals(task.getSignerId(), signer.identity())) {
            tasks.finish(id, token, "FAILED", "STORAGE_CONFIG_CHANGED", now()); return;
        }
        if (!eligible(task)) {
            tasks.finish(id, token, "FAILED", "RESOURCE_STATE_CHANGED", now()); return;
        }
        try { cleaner.deleteObject(task.getStorageKey()); }
        catch (Exception ex) {
            LocalDateTime failed = now();
            if (task.getAttemptCount() >= properties.maxAttempts()) {
                tasks.finish(id, token, "FAILED", "STORAGE_DELETE_FAILED", failed);
            } else {
                long delay = Math.min(60L, properties.retryBaseSeconds() * (1L << Math.min(10, task.getAttemptCount()-1)));
                tasks.retry(id, token, failed, failed.plusSeconds(delay));
            }
            log.warn("Upload cleanup storage operation failed, taskId={}, errorType={}", id, ex.getClass().getSimpleName());
            return;
        }
        tasks.finish(id, token, "SUCCEEDED", null, now());
    }

    private boolean eligible(UploadCleanupTask task) {
        MediaUpload upload = uploads.selectById(task.getUploadId());
        MediaAsset asset = assets.selectById(task.getAssetId());
        return upload != null && asset != null && "CANCELLED".equals(upload.getState()) && "FAILED".equals(asset.getState())
                && Objects.equals(task.getOwnerId(), upload.getOwnerId()) && Objects.equals(task.getOwnerId(), asset.getOwnerId())
                && Objects.equals(task.getAssetId(), upload.getAssetId()) && Objects.equals(task.getSignerId(), upload.getSignerId())
                && Objects.equals(task.getStorageKey(), upload.getStorageKey()) && Objects.equals(task.getStorageKey(), asset.getStorageKey())
                && task.getStorageKey().startsWith("private/" + task.getOwnerId() + "/" + task.getAssetId() + "/");
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
