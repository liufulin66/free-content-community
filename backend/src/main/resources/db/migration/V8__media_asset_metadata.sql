ALTER TABLE media_asset
    ADD COLUMN duration_seconds DECIMAL(20,6) NULL AFTER actual_size_bytes,
    ADD COLUMN width INT NULL AFTER duration_seconds,
    ADD COLUMN height INT NULL AFTER width,
    ADD CONSTRAINT chk_media_asset_duration CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    ADD CONSTRAINT chk_media_asset_width CHECK (width IS NULL OR width >= 1),
    ADD CONSTRAINT chk_media_asset_height CHECK (height IS NULL OR height >= 1);
