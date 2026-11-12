ALTER TABLE community_content
    ADD COLUMN first_published_at DATETIME(6) NULL,
    ADD COLUMN published_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_content_publication_times CHECK (
        (first_published_at IS NULL AND published_at IS NULL)
        OR (first_published_at IS NOT NULL AND published_at IS NOT NULL AND first_published_at<=published_at)
    );

ALTER TABLE content_review_outbox DROP CHECK chk_review_outbox_type;
ALTER TABLE content_review_outbox ADD CONSTRAINT chk_review_outbox_type
    CHECK (event_type IN ('CONTENT_REVIEW_SUBMITTED','CONTENT_REVIEW_DECIDED','CONTENT_PUBLISHED'));

CREATE TABLE content_review_decision_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    before_content_version INT NOT NULL,
    after_content_version INT NOT NULL,
    before_review_version INT NOT NULL,
    after_review_version INT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_decision_audit (review_id),
    KEY idx_review_decision_actor (actor_id,occurred_at,id),
    CONSTRAINT fk_review_decision_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_review_decision_review FOREIGN KEY (review_id,content_id) REFERENCES content_review(id,content_id),
    CONSTRAINT fk_review_decision_revision FOREIGN KEY (revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_review_decision_action CHECK (decision IN ('APPROVE','REJECT')),
    CONSTRAINT chk_review_decision_content_version CHECK (before_content_version>=1 AND after_content_version=before_content_version+1),
    CONSTRAINT chk_review_decision_review_version CHECK (before_review_version>=1 AND after_review_version=before_review_version+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_review_decision_request (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_snapshot JSON NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_decision_request_scope (created_by,key_hash),
    KEY idx_review_decision_request_expiry (expires_at),
    CONSTRAINT fk_review_decision_request_actor FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_review_decision_request_review FOREIGN KEY (review_id,content_id) REFERENCES content_review(id,content_id),
    CONSTRAINT chk_review_decision_request_state CHECK (state IN ('PROCESSING','SUCCEEDED')),
    CONSTRAINT chk_review_decision_request_result CHECK (
        (state='PROCESSING' AND content_id IS NULL AND result_snapshot IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state='SUCCEEDED' AND content_id IS NOT NULL AND result_snapshot IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
