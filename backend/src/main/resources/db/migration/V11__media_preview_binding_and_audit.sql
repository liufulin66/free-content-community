ALTER TABLE media_asset
    ADD COLUMN ready_object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN ready_object_etag VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN ready_storage_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT chk_media_ready_binding CHECK (
        (ready_object_key IS NULL AND ready_object_etag IS NULL AND ready_storage_id IS NULL)
        OR (ready_object_key IS NOT NULL AND CHAR_LENGTH(ready_object_key)>0
            AND ready_object_etag IS NOT NULL AND CHAR_LENGTH(ready_object_etag)>0
            AND ready_storage_id IS NOT NULL AND ready_storage_id REGEXP '^[a-f0-9]{64}$')
    );

CREATE TABLE content_media_preview_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_media_preview_actor (actor_id,occurred_at,id),
    KEY idx_media_preview_target (content_id,revision_id,asset_id),
    CONSTRAINT fk_media_preview_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_media_preview_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_media_preview_revision FOREIGN KEY (revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT fk_media_preview_asset FOREIGN KEY (asset_id) REFERENCES media_asset(id),
    CONSTRAINT chk_media_preview_action CHECK (action='CREATOR_REVISION_MEDIA_PREVIEW'),
    CONSTRAINT chk_media_preview_expiry CHECK (expires_at>occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
