package com.freecontent.community.media.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("media_processing_task")
public class MediaProcessingTask {
    private String id;
    private String ownerId;
    private String uploadId;
    private String assetId;
    private String type;
    private String state;
    private Integer progressPercent;
    private Boolean retryable;
    private String errorCode;
    private String errorMessage;
    private String retryOfTaskId;
    private String storageKey;
    private String signerId;
    private String objectEtag;
    private String objectVersionId;
    private Long objectSizeBytes;
    private String objectContentType;
    private LocalDateTime objectLastModified;
    private String expectedSha256;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
