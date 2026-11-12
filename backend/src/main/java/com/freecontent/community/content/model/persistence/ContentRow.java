package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;
import com.freecontent.community.content.model.ContentKind;

/** 模块内部的数据投影，不依赖具体查询 Mapper 或 HTTP 响应。 */
public record ContentRow(String id, String authorId, ContentKind kind, String lifecycle, String publicationStatus, int version,
            String workingRevisionId, String publishedRevisionId, String activeReviewId,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) { }
