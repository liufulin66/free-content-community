package com.freecontent.community.tag.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.freecontent.community.tag.model.TagStatus;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("tag_creation_request")
public class TagCreationRequestRecord {
    private String id;
    private String createdBy;
    private String keyHash;
    private String fingerprint;
    private String state;
    private String tagId;
    private String resultName;
    private String resultDescription;
    private TagStatus resultStatus;
    private Integer resultVersion;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
