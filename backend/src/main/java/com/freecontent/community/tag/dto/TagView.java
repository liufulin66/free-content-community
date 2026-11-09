package com.freecontent.community.tag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Tag", requiredProperties = {"id", "name", "description", "status", "version"})
public record TagView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(minLength = 1, maxLength = 30) String name,
        @Schema(maxLength = 200) String description,
        @Schema(allowableValues = {"ACTIVE", "DISABLED"}) String status,
        @Schema(minimum = "1", maximum = "2147483647") int version) { }
