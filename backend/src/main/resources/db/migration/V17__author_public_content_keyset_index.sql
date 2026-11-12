-- 作者公开作品：作者和公开状态等值定位，再按首次发布时间与ID进行键集扫描。
-- 仅新增索引，不修改既有内容、修订、发布指针或已执行迁移。
CREATE INDEX idx_content_author_public_seek
    ON community_content (author_id, lifecycle, publication_status, first_published_at, id);
