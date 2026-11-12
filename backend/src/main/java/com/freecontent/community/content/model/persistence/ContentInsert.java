package com.freecontent.community.content.model.persistence;

import com.freecontent.community.content.model.ContentKind;

/** 数据库写入参数，与 HTTP 请求及响应分开演进。 */
public record ContentInsert(String id, String authorId, ContentKind kind) { }
