package com.freecontent.community.media.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("media_upload_request")
public class UploadRequestRecord {
    private String id;
    private String ownerId;
    private String keyHash;
    private String fingerprint;
    private String state;
    private String uploadId;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
