package com.freecontent.community.media.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"id", "assetId", "strategy", "state", "version", "partSizeBytes", "totalParts", "uploadedParts", "singleUploadTarget", "expiresAt"})
public record UploadSession(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")
        String id,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")
        String assetId,
        @Schema(allowableValues = {"SINGLE", "MULTIPART"})
        String strategy,
        @Schema(allowableValues = {"UPLOADING", "COMPLETED", "CANCELLED", "EXPIRED"})
        String state,
        @Schema(minimum = "1", maximum = "2147483647")
        int version,
        @Schema(types = {"integer", "null"}, minimum = "1")
        Long partSizeBytes,
        @Schema(minimum = "1", maximum = "10000")
        int totalParts,
        List<UploadedPart> uploadedParts,
        @Schema(nullable = true)
        UploadTarget singleUploadTarget, Instant expiresAt) {

    @Schema(requiredProperties = {"partNumber", "etag", "sizeBytes"})
    public record UploadedPart(@Schema(minimum = "1", maximum = "10000")
            int partNumber,
            String etag, @Schema(minimum = "1")
            long sizeBytes) {

    }
}
