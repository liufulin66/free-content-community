package com.freecontent.community.content.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"review", "contentVersion"})
public record ReviewResult(ReviewView review, @Schema(minimum = "1", maximum = "2147483647") int contentVersion) { }
