package com.freecontent.community.media.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.media.upload-policy")
public record UploadPolicyProperties(long maxImageBytes, long maxVideoBytes, List<String> allowedImageMimeTypes,
        List<String> allowedVideoMimeTypes, long multipartThresholdBytes,
        int maxPartCount, int uploadUrlTtlSeconds) {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public UploadPolicyProperties {
        if (maxImageBytes < 1 || maxImageBytes > MAX_SAFE_INTEGER
                || maxVideoBytes < 1 || maxVideoBytes > MAX_SAFE_INTEGER
                || multipartThresholdBytes < 1 || maxPartCount < 1 || maxPartCount > 10000
                || uploadUrlTtlSeconds < 1) {
            throw new IllegalArgumentException("上传策略数值配置不符合契约范围");
        }
        allowedImageMimeTypes = List.copyOf(allowedImageMimeTypes);
        allowedVideoMimeTypes = List.copyOf(allowedVideoMimeTypes);
    }
}
