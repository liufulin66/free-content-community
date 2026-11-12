package com.freecontent.community.content.browse.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(name = "ContentPage", requiredProperties = {"items", "nextCursor", "hasMore", "snapshotAt"})
public record ContentPageView(List<PublicContentSummaryView> items,
        @Schema(types = {"string", "null"}) String nextCursor, boolean hasMore, Instant snapshotAt) {
    public ContentPageView {
        items = List.copyOf(items);
        if (hasMore ? nextCursor == null || nextCursor.isEmpty() : nextCursor != null) throw new IllegalArgumentException("分页状态与游标不一致");
    }
}
