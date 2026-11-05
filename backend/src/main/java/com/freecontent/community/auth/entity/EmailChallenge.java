package com.freecontent.community.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 邮箱验证码申请。所有日期时间字段按 UTC 保存和读取。
 * 数据库生成列 active_slot 不映射到实体，由 MySQL 自行维护。
 */
@Getter
@Setter
@TableName("email_challenge")
public class EmailChallenge {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String emailNormalized;

    private String deliveryEmail;

    private EmailChallengePurpose purpose;

    private byte[] codeMac;

    private String macKeyId;

    private Status status;

    private Integer failedAttempts = 0;

    private LocalDateTime expiresAt;

    private LocalDateTime resendAfter;

    private LocalDateTime consumedAt;

    private LocalDateTime invalidatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum Status {
        ACTIVE,
        CONSUMED,
        INVALIDATED,
        LOCKED
    }
}
