package com.freecontent.community.content.model.persistence;

import java.time.LocalDateTime;

/** 模块内部的数据投影，不依赖具体查询 Mapper 或 HTTP 响应。 */
public record ReviewRow(String id, String contentId, String revisionId, String purpose, String state, int version,
            LocalDateTime submittedAt, LocalDateTime decidedAt, String reviewerId, String reason) { }
