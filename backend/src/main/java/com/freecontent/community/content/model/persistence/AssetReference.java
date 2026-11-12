package com.freecontent.community.content.model.persistence;


/** 模块内部的数据投影，不依赖具体查询 Mapper 或 HTTP 响应。 */
public record AssetReference(String assetId, String usageType, int position) { }
