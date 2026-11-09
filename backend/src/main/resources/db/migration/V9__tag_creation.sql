CREATE TABLE community_tag (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL,
    description VARCHAR(200) NOT NULL DEFAULT '',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 1,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_tag_name (name),
    KEY idx_community_tag_status (status, name),
    CONSTRAINT fk_community_tag_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT chk_community_tag_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 30),
    CONSTRAINT chk_community_tag_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT chk_community_tag_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tag_creation_request (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tag_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_name VARCHAR(30) NULL,
    result_description VARCHAR(200) NULL,
    result_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_version INT NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_creation_scope (created_by, key_hash),
    KEY idx_tag_creation_expiry (expires_at),
    CONSTRAINT fk_tag_creation_actor FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_tag_creation_tag FOREIGN KEY (tag_id) REFERENCES community_tag (id),
    CONSTRAINT chk_tag_creation_state CHECK (state IN ('PROCESSING','SUCCEEDED')),
    CONSTRAINT chk_tag_creation_snapshot CHECK (
        (state='PROCESSING' AND tag_id IS NULL AND result_name IS NULL AND result_description IS NULL
            AND result_status IS NULL AND result_version IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state='SUCCEEDED' AND tag_id IS NOT NULL AND result_name IS NOT NULL AND result_description IS NOT NULL
            AND result_status IN ('ACTIVE','DISABLED') AND result_version >= 1 AND lease_token IS NULL AND lease_until IS NULL
            AND result_status IS NOT NULL AND result_version IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
