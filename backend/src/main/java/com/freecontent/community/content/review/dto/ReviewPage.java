package com.freecontent.community.content.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigInteger;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "pageSize", "total"})
public record ReviewPage(List<ReviewView> items,
        @Schema(type = "integer", minimum = "1") BigInteger page,
        @Schema(minimum = "1", maximum = "100") int pageSize,
        @Schema(minimum = "0") long total) {
    public ReviewPage { items = List.copyOf(items); }
}
