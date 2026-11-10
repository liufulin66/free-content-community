CREATE TABLE community_content (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lifecycle VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publication_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INT NOT NULL,
    working_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    published_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    active_review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_content_author (author_id, created_at, id),
    CONSTRAINT fk_content_author FOREIGN KEY (author_id) REFERENCES app_user(id),
    CONSTRAINT chk_content_kind CHECK (kind IN ('ARTICLE','VIDEO')),
    CONSTRAINT chk_content_lifecycle CHECK (lifecycle IN ('ACTIVE','DELETED')),
    CONSTRAINT chk_content_publication CHECK (publication_status IN ('UNPUBLISHED','PUBLISHED','WITHDRAWN','TAKEN_DOWN')),
    CONSTRAINT chk_content_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_revision (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(120) NOT NULL,
    body_markdown MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    working_content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status IN ('DRAFT','IN_REVIEW') THEN content_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_revision_number (content_id, revision_number),
    UNIQUE KEY uk_revision_identity (id, content_id),
    UNIQUE KEY uk_revision_working (working_content_id),
    CONSTRAINT fk_revision_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT chk_revision_number CHECK (revision_number >= 1),
    CONSTRAINT chk_revision_status CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT chk_revision_body CHECK (CHAR_LENGTH(body_markdown) <= 100000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE community_content
    ADD CONSTRAINT fk_content_working FOREIGN KEY (working_revision_id,id) REFERENCES content_revision(id,content_id),
    ADD CONSTRAINT fk_content_published FOREIGN KEY (published_revision_id,id) REFERENCES content_revision(id,content_id);

CREATE TABLE content_revision_asset (
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    usage_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (revision_id, usage_type, position),
    UNIQUE KEY uk_revision_asset (revision_id, usage_type, asset_id),
    CONSTRAINT fk_revision_asset_revision FOREIGN KEY (revision_id) REFERENCES content_revision(id),
    CONSTRAINT fk_revision_asset_asset FOREIGN KEY (asset_id) REFERENCES media_asset(id),
    CONSTRAINT chk_revision_asset_usage CHECK (usage_type IN ('COVER','IMAGE','VIDEO')),
    CONSTRAINT chk_revision_asset_position CHECK (position >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_revision_tag (
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tag_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (revision_id, position),
    UNIQUE KEY uk_revision_tag (revision_id, tag_id),
    CONSTRAINT fk_revision_tag_revision FOREIGN KEY (revision_id) REFERENCES content_revision(id),
    CONSTRAINT fk_revision_tag_tag FOREIGN KEY (tag_id) REFERENCES community_tag(id),
    CONSTRAINT chk_revision_tag_position CHECK (position >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_creation_request (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_snapshot JSON NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_creation_scope (created_by, key_hash),
    KEY idx_content_creation_expiry (expires_at),
    CONSTRAINT fk_content_creation_actor FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_content_creation_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT chk_content_creation_state CHECK (state IN ('PROCESSING','SUCCEEDED')),
    CONSTRAINT chk_content_creation_snapshot CHECK (
        (state='PROCESSING' AND content_id IS NULL AND result_snapshot IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state='SUCCEEDED' AND content_id IS NOT NULL AND result_snapshot IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
