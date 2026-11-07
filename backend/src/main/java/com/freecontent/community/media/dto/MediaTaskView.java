package com.freecontent.community.media.dto;

import com.freecontent.community.media.entity.MediaProcessingTask;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.ZoneOffset;

@Schema(name = "Task", requiredProperties = {"id", "type", "state", "progressPercent", "assetId", "retryable",
        "errorCode", "errorMessage", "createdAt", "updatedAt", "retryOfTaskId"})
public record MediaTaskView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        @Schema(allowableValues = {"MEDIA_PROCESSING", "INDEX_SYNC", "NOTIFICATION_DELIVERY", "EVENT_DELIVERY"}) String type,
        @Schema(allowableValues = {"PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"}) String state,
        @Schema(minimum = "0", maximum = "100") int progressPercent,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String assetId,
        boolean retryable,
        @Schema(types = {"string", "null"}) String errorCode,
        @Schema(types = {"string", "null"}) String errorMessage,
        Instant createdAt, Instant updatedAt,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String retryOfTaskId) {
    public static MediaTaskView from(MediaProcessingTask task) {
        return new MediaTaskView(task.getId(), task.getType(), task.getState(), task.getProgressPercent(), task.getAssetId(),
                Boolean.TRUE.equals(task.getRetryable()), task.getErrorCode(), task.getErrorMessage(),
                task.getCreatedAt().toInstant(ZoneOffset.UTC), task.getUpdatedAt().toInstant(ZoneOffset.UTC), task.getRetryOfTaskId());
    }
}
