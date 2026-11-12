CREATE TABLE content_review (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INT NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    reviewer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    pending_content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN state='PENDING' THEN content_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_identity (id,content_id),
    UNIQUE KEY uk_review_revision (revision_id),
    UNIQUE KEY uk_review_pending (pending_content_id),
    KEY idx_review_queue (state,submitted_at,id),
    CONSTRAINT fk_review_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_review_revision FOREIGN KEY (revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT fk_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user(id),
    CONSTRAINT chk_review_purpose CHECK (purpose IN ('INITIAL','UPDATE','REMEDIATION')),
    CONSTRAINT chk_review_state CHECK (state IN ('PENDING','APPROVED','REJECTED','CANCELLED','INVALIDATED')),
    CONSTRAINT chk_review_version CHECK (version>=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE community_content ADD CONSTRAINT fk_content_active_review
    FOREIGN KEY (active_review_id,id) REFERENCES content_review(id,content_id);

CREATE TABLE content_review_outbox (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_version INT NOT NULL,
    event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_outbox_event (review_id,event_type),
    KEY idx_review_outbox_pending (state,created_at,id),
    CONSTRAINT fk_review_outbox_review FOREIGN KEY (review_id,content_id) REFERENCES content_review(id,content_id),
    CONSTRAINT fk_review_outbox_revision FOREIGN KEY (revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_review_outbox_version CHECK (content_version>=1),
    CONSTRAINT chk_review_outbox_type CHECK (event_type='CONTENT_REVIEW_SUBMITTED'),
    CONSTRAINT chk_review_outbox_state CHECK (state IN ('PENDING','PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_review_submission_request (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_snapshot JSON NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_request_scope (created_by,key_hash),
    KEY idx_review_request_expiry (expires_at),
    CONSTRAINT fk_review_request_actor FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_review_request_review FOREIGN KEY (review_id,content_id) REFERENCES content_review(id,content_id),
    CONSTRAINT chk_review_request_state CHECK (state IN ('PROCESSING','SUCCEEDED')),
    CONSTRAINT chk_review_request_snapshot CHECK (
        (state='PROCESSING' AND review_id IS NULL AND result_snapshot IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state='SUCCEEDED' AND review_id IS NOT NULL AND result_snapshot IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
