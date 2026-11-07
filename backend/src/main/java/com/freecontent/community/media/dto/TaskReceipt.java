package com.freecontent.community.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"taskId", "assetId", "state", "statusUrl"})
public record TaskReceipt(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String taskId,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String assetId,
        @Schema(allowableValues = {"PENDING", "RUNNING"}) String state,
        String statusUrl) {
    public static TaskReceipt pending(String taskId, String assetId) {
        return new TaskReceipt(taskId, assetId, "PENDING", "/api/v1/tasks/" + taskId);
    }
}
