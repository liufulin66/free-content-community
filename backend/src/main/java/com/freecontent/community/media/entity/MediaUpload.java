package com.freecontent.community.media.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("media_upload_session")
public class MediaUpload {
    private String id;
    private String assetId;
    private String ownerId;
    private String storageKey;
    private String contentType;
    private String state;
    private Integer version;
    private LocalDateTime signatureAt;
    private Integer signatureTtlSeconds;
    private String signerId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
