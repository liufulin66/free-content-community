package com.freecontent.community.content.dto;

import com.freecontent.community.content.model.ContentKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"id", "authorId", "kind", "lifecycle", "publicationStatus", "version", "workingRevision",
        "publishedRevision", "activeReviewId", "createdAt", "updatedAt", "deletedAt"})
public record ContentWorkspace(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String authorId, ContentKind kind,
        @Schema(allowableValues = {"ACTIVE", "DELETED"}) String lifecycle,
        @Schema(allowableValues = {"UNPUBLISHED", "PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"}) String publicationStatus,
        @Schema(minimum = "1", maximum = "2147483647") int version,
        @Schema(types = {"object", "null"}, schemaResolution = Schema.SchemaResolution.INLINE) RevisionView workingRevision,
        @Schema(types = {"object", "null"}, schemaResolution = Schema.SchemaResolution.INLINE) RevisionView publishedRevision,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String activeReviewId, Instant createdAt, Instant updatedAt,
        @Schema(types = {"string", "null"}, format = "date-time") Instant deletedAt) { }
