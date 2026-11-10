package com.freecontent.community.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import java.time.Instant;
import java.util.List;

@Schema(name = "Revision", requiredProperties = {"id", "contentId", "revisionNumber", "status", "title", "bodyMarkdown",
        "coverAssetId", "imageAssetIds", "videoAssetId", "tagIds", "createdAt", "updatedAt"})
public record RevisionView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String contentId,
        @Schema(minimum = "1") int revisionNumber,
        @Schema(allowableValues = {"DRAFT", "IN_REVIEW", "APPROVED", "REJECTED", "CANCELLED"}) String status,
        @Schema(maxLength = 120) String title, @Schema(maxLength = 100000) String bodyMarkdown,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String coverAssetId,
        @ArraySchema(maxItems = 50, uniqueItems = true, schema = @Schema(type = "string", minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) List<String> imageAssetIds,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String videoAssetId,
        @ArraySchema(maxItems = 5, uniqueItems = true, schema = @Schema(type = "string", minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) List<String> tagIds,
        Instant createdAt, Instant updatedAt) {
    public RevisionView {
        imageAssetIds = List.copyOf(imageAssetIds);
        tagIds = List.copyOf(tagIds);
    }
}
