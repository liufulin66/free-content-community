package com.freecontent.community.content.model.persistence;


/** 数据库写入参数，与 HTTP 请求及响应分开演进。 */
public record ReviewInsert(String id, String contentId, String revisionId, String purpose) { }
