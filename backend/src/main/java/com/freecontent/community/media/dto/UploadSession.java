package com.freecontent.community.media.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"id", "assetId", "strategy", "state", "version", "partSizeBytes", "totalParts", "uploadedParts", "singleUploadTarget", "expiresAt"})
public record UploadSession(
        //上传会话的唯一标识符id
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")
        String id,
        //上传会话对应的资源id
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")
        String assetId,
        //上传模式
        @Schema(allowableValues = {"SINGLE", "MULTIPART"})
        String strategy,
        //会话到期状态
        @Schema(allowableValues = {"UPLOADING", "COMPLETED", "CANCELLED", "EXPIRED"})
        String state,
        //上传会话的版本号
        @Schema(minimum = "1", maximum = "2147483647")
        int version,
        //上传会话的分片大小，单位为字节
        @Schema(types = {"integer", "null"}, minimum = "1")
        Long partSizeBytes,
        //由存储服务确认的分片清单
        @Schema(minimum = "1", maximum = "10000")
        int totalParts,
        List<UploadedPart> uploadedParts,
        @Schema(types = {"object", "null"}, implementation = UploadTarget.class, schemaResolution = Schema.SchemaResolution.INLINE)
        UploadTarget singleUploadTarget, Instant expiresAt) {

    @Schema(requiredProperties = {"partNumber", "etag", "sizeBytes"})
    public record UploadedPart(@Schema(minimum = "1", maximum = "10000")
            int partNumber,
            String etag, @Schema(minimum = "1")
            long sizeBytes) {

    }
}
