package com.freecontent.community.content.browse.model;

import com.freecontent.community.content.model.ContentKind;
import java.time.Instant;

/** 已校验的连续浏览条件；快照上界和过期时间在翻页过程中保持不变。 */
public record PublicContentListQuery(int limit, ContentKind kind, String tagId, String scope,
        Instant snapshotAt, Instant expiresAt, Instant lastFirstPublishedAt, String lastId, String authorId) {
    public PublicContentListQuery(int limit, ContentKind kind, String tagId, String scope,
            Instant snapshotAt, Instant expiresAt, Instant lastFirstPublishedAt, String lastId) {
        this(limit, kind, tagId, scope, snapshotAt, expiresAt, lastFirstPublishedAt, lastId, null);
    }
}
