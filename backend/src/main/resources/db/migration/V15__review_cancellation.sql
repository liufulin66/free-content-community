ALTER TABLE content_review_outbox DROP CHECK chk_review_outbox_type;
ALTER TABLE content_review_outbox ADD CONSTRAINT chk_review_outbox_type
    CHECK (event_type IN ('CONTENT_REVIEW_SUBMITTED','CONTENT_REVIEW_DECIDED','CONTENT_PUBLISHED','CONTENT_REVIEW_CANCELLED'));

CREATE TABLE content_review_cancellation_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_content_version INT NOT NULL,
    after_content_version INT NOT NULL,
    before_review_version INT NOT NULL,
    after_review_version INT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_cancellation (review_id),
    KEY idx_review_cancellation_actor (actor_id,occurred_at,id),
    CONSTRAINT fk_review_cancellation_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_review_cancellation_review FOREIGN KEY (review_id,content_id) REFERENCES content_review(id,content_id),
    CONSTRAINT fk_review_cancellation_revision FOREIGN KEY (revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_review_cancellation_content_version CHECK (before_content_version>=1 AND after_content_version=before_content_version+1),
    CONSTRAINT chk_review_cancellation_review_version CHECK (before_review_version>=1 AND after_review_version=before_review_version+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
