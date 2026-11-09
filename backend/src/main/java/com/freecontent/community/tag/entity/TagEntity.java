package com.freecontent.community.tag.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.freecontent.community.tag.model.TagStatus;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("community_tag")
public class TagEntity {
    private String id;
    private String name;
    private String description;
    private TagStatus status;
    private Integer version;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
