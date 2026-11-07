package com.freecontent.community.media.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("media_upload_cleanup")
public class UploadCleanupTask {
    private String id;
    private String ownerId;
    private String uploadId;
    private String assetId;
    private String storageKey;
    private String signerId;
    private String state;
    private Integer attemptCount;
    private LocalDateTime availableAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
