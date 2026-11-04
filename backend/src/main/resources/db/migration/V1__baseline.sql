-- 骨架阶段的基线迁移，仅用于验证 Flyway 链路。
-- 后续业务表按契约分版本新增，不要修改已执行的迁移文件。
CREATE TABLE app_setting (
    setting_key   VARCHAR(64)  NOT NULL COMMENT '配置键',
    setting_value VARCHAR(512) NOT NULL COMMENT '配置值',
    updated_at    DATETIME(6)  NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (setting_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '应用配置表（骨架示例）';

INSERT INTO app_setting (setting_key, setting_value, updated_at)
VALUES ('schema.version', '1', UTC_TIMESTAMP(6));
