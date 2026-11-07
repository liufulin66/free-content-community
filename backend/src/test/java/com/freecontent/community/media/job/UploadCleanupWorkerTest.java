package com.freecontent.community.media.job;

import com.freecontent.community.media.config.UploadCleanupProperties;
import com.freecontent.community.media.entity.*;
import com.freecontent.community.media.mapper.*;
import com.freecontent.community.media.storage.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UploadCleanupWorkerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 0, 0);
    private final UploadCleanupTaskMapper tasks = mock(UploadCleanupTaskMapper.class);
    private final MediaUploadMapper uploads = mock(MediaUploadMapper.class);
    private final MediaAssetMapper assets = mock(MediaAssetMapper.class);
    private final UploadObjectCleaner cleaner = mock(UploadObjectCleaner.class);
    private final UploadSigner signer = mock(UploadSigner.class);

    @Test
    void deletesTheCancelledPrivateObjectBeforeMarkingTheClaimedTaskSuccessful() throws Exception {
        UploadCleanupTask task = fixture();
        worker().processTask(task.getId());
        var order = inOrder(cleaner, tasks);
        order.verify(cleaner).deleteObject(task.getStorageKey());
        order.verify(tasks).finish(eq(task.getId()), eq(task.getLeaseToken()), eq("SUCCEEDED"), isNull(), eq(NOW));
    }

    @Test
    void futureOrAlreadyClaimedTaskDoesNotTouchStorage() throws Exception {
        UploadCleanupTask task = fixture();
        when(tasks.claim(eq(task.getId()), anyString(), eq(NOW), eq(NOW.plusSeconds(60)))).thenReturn(0);
        worker().processTask(task.getId());
        verifyNoInteractions(cleaner);
    }

    @Test
    void temporaryStorageFailureReturnsTheTaskToPendingWithBackoff() throws Exception {
        UploadCleanupTask task = fixture();
        doThrow(new IOException("simulated")).when(cleaner).deleteObject(task.getStorageKey());
        worker().processTask(task.getId());
        verify(tasks).retry(task.getId(), task.getLeaseToken(), NOW, NOW.plusSeconds(5));
        verify(tasks, never()).finish(anyString(), anyString(), anyString(), nullable(String.class), any());
    }

    @Test
    void lastStorageAttemptLeavesAnInspectableFailedTask() throws Exception {
        UploadCleanupTask task = fixture();
        task.setAttemptCount(3);
        doThrow(new IOException("simulated")).when(cleaner).deleteObject(task.getStorageKey());
        worker().processTask(task.getId());
        verify(tasks).finish(task.getId(), task.getLeaseToken(), "FAILED", "STORAGE_DELETE_FAILED", NOW);
        verify(tasks, never()).retry(anyString(), anyString(), any(), any());
    }

    @Test
    void exhaustedCrashRecoveryDoesNotDeleteAgain() throws Exception {
        UploadCleanupTask task = fixture();
        task.setAttemptCount(4);
        worker().processTask(task.getId());
        verify(tasks).finish(task.getId(), task.getLeaseToken(), "FAILED", "ATTEMPTS_EXHAUSTED", NOW);
        verifyNoInteractions(cleaner);
    }

    @Test
    void readyAssetIsProtectedEvenIfTheCleanupRecordExists() throws Exception {
        UploadCleanupTask task = fixture();
        assets.selectById("asset1").setState("READY");
        worker().processTask(task.getId());
        verify(tasks).finish(task.getId(), task.getLeaseToken(), "FAILED", "RESOURCE_STATE_CHANGED", NOW);
        verifyNoInteractions(cleaner);
    }

    @Test
    void changedStorageConfigurationCannotDeleteFromAnotherBackend() throws Exception {
        UploadCleanupTask task = fixture();
        when(signer.identity()).thenReturn("changed-backend");
        worker().processTask(task.getId());
        verify(tasks).finish(task.getId(), task.getLeaseToken(), "FAILED", "STORAGE_CONFIG_CHANGED", NOW);
        verifyNoInteractions(cleaner);
    }

    @Test
    void losingTheClaimBeforeReadingTheTaskDoesNotDeleteOrAcknowledge() throws Exception {
        UploadCleanupTask task = fixture();
        when(tasks.selectById(task.getId())).thenAnswer(invocation -> { task.setLeaseToken("different-owner"); return task; });
        worker().processTask(task.getId());
        verifyNoInteractions(cleaner);
        verify(tasks, never()).finish(anyString(), anyString(), anyString(), nullable(String.class), any());
    }

    @Test
    void disabledWorkerDoesNotScanTheQueue() {
        new UploadCleanupWorker(tasks, uploads, assets, cleaner, signer,
                new UploadCleanupProperties(false, 600, 1000, 60, 3, 5), Clock.systemUTC()).poll();
        verifyNoInteractions(tasks, cleaner);
    }

    private UploadCleanupWorker worker() {
        return new UploadCleanupWorker(tasks, uploads, assets, cleaner, signer,
                new UploadCleanupProperties(true, 600, 1000, 60, 3, 5), Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private UploadCleanupTask fixture() {
        UploadCleanupTask task = new UploadCleanupTask();
        task.setId("cleanup1"); task.setOwnerId("user1"); task.setUploadId("upload1"); task.setAssetId("asset1");
        task.setStorageKey("private/user1/asset1/source"); task.setSignerId("signer1"); task.setAttemptCount(1);
        when(tasks.claim(eq(task.getId()), anyString(), eq(NOW), eq(NOW.plusSeconds(60)))).thenAnswer(invocation -> {
            task.setLeaseToken(invocation.getArgument(1)); task.setState("RUNNING"); return 1;
        });
        when(tasks.selectById(task.getId())).thenReturn(task);
        MediaUpload upload = new MediaUpload();
        upload.setId("upload1"); upload.setAssetId("asset1"); upload.setOwnerId("user1"); upload.setState("CANCELLED");
        upload.setStorageKey(task.getStorageKey()); upload.setSignerId("signer1");
        when(uploads.selectById("upload1")).thenReturn(upload);
        MediaAsset asset = new MediaAsset();
        asset.setId("asset1"); asset.setOwnerId("user1"); asset.setState("FAILED"); asset.setStorageKey(task.getStorageKey());
        when(assets.selectById("asset1")).thenReturn(asset);
        when(signer.identity()).thenReturn("signer1");
        return task;
    }
}
