package com.freecontent.community.user.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** 内部持久化对象，接口统一返回CurrentUser，不直接序列化该实体。 */
@Getter
@Setter
@TableName("app_user")
public class UserAccount {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String email;
    private String emailNormalized;
    private String passwordHash;
    private Boolean emailVerified;
    private LocalDateTime emailVerifiedAt;
    private String displayName;
    private String bio;
    private String avatarAssetId;
    private Status accountStatus;
    private Integer version;
    private Long authVersion = 1L;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        ACTIVE, WRITE_BLOCKED, DISABLED
    }
}
