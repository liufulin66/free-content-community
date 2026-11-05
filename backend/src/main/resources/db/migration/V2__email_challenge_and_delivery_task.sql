CREATE TABLE email_challenge (
    id                  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                        NOT NULL COMMENT '验证码申请ID，对外作为challengeId',
    email_normalized    VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin
                        NOT NULL COMMENT '统一规范化后的邮箱，用于匹配和限流',
    delivery_email      VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin
                        NOT NULL COMMENT '去除首尾空白后保留原始大小写的投递邮箱',
    purpose             VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
                        NOT NULL COMMENT 'REGISTER / PASSWORD_RESET',
    code_mac            BINARY(32) NOT NULL COMMENT '验证码HMAC-SHA256摘要',
    mac_key_id          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                        NOT NULL COMMENT '摘要密钥标识，密钥本身不入库',
    status              VARCHAR(16) NOT NULL COMMENT
                        'ACTIVE / CONSUMED / INVALIDATED / LOCKED',
    failed_attempts     INT UNSIGNED NOT NULL DEFAULT 0
                        COMMENT '验证码校验失败次数',
    expires_at          DATETIME(6) NOT NULL COMMENT '失效时间，UTC',
    resend_after        DATETIME(6) NOT NULL COMMENT '允许再次申请时间，UTC',
    consumed_at         DATETIME(6) NULL COMMENT '成功消费时间，UTC',
    invalidated_at      DATETIME(6) NULL COMMENT '被新申请替代等失效时间，UTC',
    created_at          DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    updated_at          DATETIME(6) NOT NULL COMMENT '更新时间，UTC',

    active_slot         TINYINT GENERATED ALWAYS AS (
                            CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
                        ) STORED COMMENT '用于约束同邮箱同用途仅一条ACTIVE记录',

    PRIMARY KEY (id),
    UNIQUE KEY uk_email_challenge_active
        (email_normalized, purpose, active_slot),
    KEY idx_email_challenge_expiry (expires_at),

    CONSTRAINT chk_email_challenge_purpose
        CHECK (purpose IN ('REGISTER', 'PASSWORD_RESET')),
    CONSTRAINT chk_email_challenge_status
        CHECK (status IN ('ACTIVE', 'CONSUMED', 'INVALIDATED', 'LOCKED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '邮箱验证码申请';


CREATE TABLE email_delivery_task (
    id                  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                        NOT NULL COMMENT '内部邮件任务ID',
    challenge_id        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                        NOT NULL COMMENT '关联验证码申请',
    code_ciphertext     VARBINARY(128) NULL
                        COMMENT 'AES-GCM加密后的验证码，含认证标签，终结后清理',
    encryption_iv       BINARY(12) NULL COMMENT '每次加密独立生成的随机IV',
    encryption_key_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                        NULL COMMENT '加密密钥标识，密钥本身不入库',
    status              VARCHAR(16) NOT NULL COMMENT
                        'PENDING / SENDING / RETRY / SENT / FAILED / CANCELLED',
    attempt_count       INT UNSIGNED NOT NULL DEFAULT 0
                        COMMENT '已开始的投递尝试次数',
    next_attempt_at     DATETIME(6) NOT NULL COMMENT '允许下次投递时间，UTC',
    lease_token         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
                        NULL COMMENT '每次认领生成的新令牌，防止旧执行者回写',
    lease_until         DATETIME(6) NULL COMMENT '认领租约到期时间，UTC',
    last_error_code     VARCHAR(64) NULL COMMENT '安全的内部错误码',
    sent_at             DATETIME(6) NULL COMMENT 'SMTP接收成功时间，UTC',
    created_at          DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    updated_at          DATETIME(6) NOT NULL COMMENT '更新时间，UTC',

    PRIMARY KEY (id),
    UNIQUE KEY uk_email_delivery_challenge (challenge_id),
    KEY idx_email_delivery_due (status, next_attempt_at),
    KEY idx_email_delivery_lease (status, lease_until),

    CONSTRAINT fk_email_delivery_challenge
        FOREIGN KEY (challenge_id) REFERENCES email_challenge (id),
    CONSTRAINT chk_email_delivery_status
        CHECK (status IN (
            'PENDING', 'SENDING', 'RETRY',
            'SENT', 'FAILED', 'CANCELLED'
        ))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '验证码邮件持久化投递任务';