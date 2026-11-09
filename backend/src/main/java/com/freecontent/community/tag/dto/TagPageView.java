package com.freecontent.community.tag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigInteger;
import java.util.List;

@Schema(name = "TagPage", requiredProperties = {"items", "page", "pageSize", "total"})
public record TagPageView(
        List<TagView> items,
        @Schema(type = "integer", minimum = "1") BigInteger page,
        @Schema(minimum = "1", maximum = "100") int pageSize,
        @Schema(minimum = "0") long total) {
    public TagPageView {
        items = List.copyOf(items);
    }
}
