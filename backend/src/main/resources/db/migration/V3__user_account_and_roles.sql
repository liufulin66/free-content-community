CREATE TABLE app_user (
    id                  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户ID',
    email               VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '保留有效大小写的邮箱',
    email_normalized    VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化邮箱，唯一身份',
    password_hash       VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '带算法标识的密码哈希',
    email_verified      BOOLEAN NOT NULL DEFAULT TRUE COMMENT '邮箱已验证',
    email_verified_at   DATETIME(6) NOT NULL COMMENT '邮箱验证完成时间，UTC',
    display_name        VARCHAR(40) NOT NULL COMMENT '公开昵称',
    bio                 VARCHAR(500) NOT NULL DEFAULT '' COMMENT '简介',
    avatar_asset_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '头像素材ID',
    account_status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / WRITE_BLOCKED / DISABLED',
    version             INT NOT NULL DEFAULT 1 COMMENT '账号资料并发版本',
    created_at          DATETIME(6) NOT NULL COMMENT '注册时间，UTC',
    updated_at          DATETIME(6) NOT NULL COMMENT '更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_email (email_normalized),
    CONSTRAINT chk_app_user_status CHECK (account_status IN ('ACTIVE', 'WRITE_BLOCKED', 'DISABLED')),
    CONSTRAINT chk_app_user_version CHECK (version >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户账号与资料';

CREATE TABLE app_user_role (
    user_id             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户ID',
    role_code           VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '预置角色代码',
    created_at          DATETIME(6) NOT NULL COMMENT '角色授予时间，UTC',
    PRIMARY KEY (user_id, role_code),
    CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT chk_app_user_role_code CHECK (role_code IN ('USER', 'MODERATOR', 'ADMIN'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户与预置角色关联';
