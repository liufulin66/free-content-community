package com.freecontent.community.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "PublicUser", requiredProperties = {"id", "displayName", "bio", "avatarUrl", "createdAt", "publishedContentCount", "followerCount"})
public record PublicUserView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(maxLength = 40) String displayName, @Schema(maxLength = 500) String bio,
        @Schema(types = {"string", "null"}, format = "uri") String avatarUrl, Instant createdAt,
        @Schema(minimum = "0") long publishedContentCount, @Schema(minimum = "0") long followerCount) { }
