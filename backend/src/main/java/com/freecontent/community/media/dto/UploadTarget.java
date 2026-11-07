package com.freecontent.community.media.dto;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"url", "method", "headers", "expiresAt"})
public record UploadTarget(@Schema(format = "uri")
        String url,
        @Schema(allowableValues = "PUT")
        String method,
        Map<String, String> headers, Instant expiresAt) {

    @Override
    public String toString() {
        return "UploadTarget[credentials=REDACTED]";
    }
}
