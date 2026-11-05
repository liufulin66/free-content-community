package com.freecontent.community.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 持久化验证码邮件任务。所有日期时间字段按 UTC 保存和读取。
 * 验证码密文等字段只用于内部投递，不通过实体自动生成日志字符串。
 */
@Getter
@Setter
@TableName("email_delivery_task")
public class EmailDeliveryTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String challengeId;

    private byte[] codeCiphertext;

    private byte[] encryptionIv;

    private String encryptionKeyId;

    private Status status;

    private Integer attemptCount = 0;

    private LocalDateTime nextAttemptAt;

    private String leaseToken;

    private LocalDateTime leaseUntil;

    private String lastErrorCode;

    private LocalDateTime sentAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum Status {
        PENDING,
        SENDING,
        RETRY,
        SENT,
        FAILED,
        CANCELLED
    }
}
