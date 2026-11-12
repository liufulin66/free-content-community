package com.freecontent.community.content.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "Review", requiredProperties = {"id", "contentId", "revisionId", "purpose", "state", "version", "submittedAt", "decidedAt", "reviewerId", "reason"})
public record ReviewView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String contentId,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String revisionId,
        @Schema(allowableValues = {"INITIAL", "UPDATE", "REMEDIATION"}) String purpose,
        @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED", "CANCELLED", "INVALIDATED"}) String state,
        @Schema(minimum = "1", maximum = "2147483647") int version,
        Instant submittedAt,
        @Schema(types = {"string", "null"}, format = "date-time") Instant decidedAt,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String reviewerId,
        @Schema(types = {"string", "null"}, maxLength = 1000) String reason) { }
