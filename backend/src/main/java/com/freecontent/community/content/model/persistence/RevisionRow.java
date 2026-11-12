package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;

/** 模块内部的数据投影，不依赖具体查询 Mapper 或 HTTP 响应。 */
public record RevisionRow(String id, String contentId, int revisionNumber, String status, String title, String bodyMarkdown,
            LocalDateTime createdAt, LocalDateTime updatedAt) { }
