package com.freecontent.community.media.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 上传策略的公开字段；存储地址、桶名和凭证不进入响应。
 */
@Schema(requiredProperties = {"maxImageBytes", "maxVideoBytes", "allowedImageMimeTypes", "allowedVideoMimeTypes",
    "multipartThresholdBytes", "maxPartCount", "uploadUrlTtlSeconds"})
public record UploadPolicy(
        @Schema(description = "图片最大字节数", minimum = "1", maximum = "9007199254740991")
        long maxImageBytes,
        @Schema(description = "视频最大字节数", minimum = "1", maximum = "9007199254740991")
        long maxVideoBytes,
        @Schema(description = "允许的图片MIME类型")
        List<String> allowedImageMimeTypes,
        @Schema(description = "允许的视频MIME类型")
        List<String> allowedVideoMimeTypes,
        @Schema(description = "分片上传阈值，单位为字节", minimum = "1")
        long multipartThresholdBytes,
        @Schema(description = "最大分片数", minimum = "1", maximum = "10000")
        int maxPartCount,
        @Schema(description = "上传签名有效秒数", minimum = "1")
        int uploadUrlTtlSeconds) {

}
