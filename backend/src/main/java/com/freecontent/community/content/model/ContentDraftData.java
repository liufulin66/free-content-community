package com.freecontent.community.content.model;

import java.util.List;

/** 创作与审核共用的草稿数据；与 HTTP 请求类型及用例分组无关。 */
public record ContentDraftData(ContentKind kind, String title, String bodyMarkdown, String coverAssetId,
                               List<String> imageAssetIds, String videoAssetId, List<String> tagIds) { }
