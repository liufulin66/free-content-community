package com.freecontent.community.media.dto;

import com.freecontent.community.media.entity.MediaAsset;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

@Schema(name = "Asset", requiredProperties = {"id", "ownerId", "scene", "state", "contentType", "sizeBytes",
        "durationSeconds", "width", "height", "createdAt"})
public record MediaAssetView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String ownerId,
        @Schema(allowableValues = {"AVATAR", "CONTENT_IMAGE", "CONTENT_VIDEO", "REPORT_ATTACHMENT"}) String scene,
        @Schema(allowableValues = {"WAITING_UPLOAD", "PROCESSING", "READY", "FAILED"}) String state,
        @Schema(description = "优先使用实际校验MIME；未知时暂用声明值，须结合state判断") String contentType,
        @Schema(minimum = "0", description = "实际大小，尚未知时为0") long sizeBytes,
        @Schema(types = {"number", "null"}, minimum = "0", description = "视频时长，非视频为null") BigDecimal durationSeconds,
        @Schema(types = {"integer", "null"}, minimum = "1") Integer width,
        @Schema(types = {"integer", "null"}, minimum = "1") Integer height,
        Instant createdAt) {
    public static MediaAssetView from(MediaAsset asset) {
        String actualType = asset.getActualContentType();
        String type = actualType == null || actualType.isBlank() ? asset.getDeclaredContentType() : actualType;
        BigDecimal duration = type.toLowerCase(Locale.ROOT).startsWith("video/") ? asset.getDurationSeconds() : null;
        return new MediaAssetView(asset.getId(), asset.getOwnerId(), asset.getScene(), asset.getState(), type,
                asset.getActualSizeBytes() == null ? 0 : asset.getActualSizeBytes(), duration,
                asset.getWidth(), asset.getHeight(), asset.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
