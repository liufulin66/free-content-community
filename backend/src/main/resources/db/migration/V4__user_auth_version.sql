ALTER TABLE app_user
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 1 COMMENT '认证版本，改密或禁用时递增' AFTER version,
    ADD CONSTRAINT chk_app_user_auth_version CHECK (auth_version >= 1);
