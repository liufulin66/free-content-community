package com.freecontent.community.media.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Getter @Setter @TableName("media_asset")
public class MediaAsset {
    private String id;
    private String ownerId;
    private String scene;
    private String fileName;
    private String declaredContentType;
    private Long declaredSizeBytes;
    private String expectedSha256;
    private String storageKey;
    private String state;
    private String actualContentType;
    private Long actualSizeBytes;
    private BigDecimal durationSeconds;
    private Integer width;
    private Integer height;
    private LocalDateTime createdAt;
}
