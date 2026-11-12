-- 公开列表先按生命周期、发布状态定位，再以首次发布时间和ID进行倒序范围扫描。
-- 保留已执行迁移；不修改内容数据、发布指针或历史时间。
CREATE INDEX idx_content_public_seek
    ON community_content (lifecycle, publication_status, first_published_at, id);
