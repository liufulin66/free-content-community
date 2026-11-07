package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.media.upload-cleanup")
public record UploadCleanupProperties(boolean enabled, int graceSeconds, int pollDelayMillis,
        int leaseSeconds, int maxAttempts, int retryBaseSeconds) {
    public static final int STORAGE_CALL_TIMEOUT_SECONDS = 10;
    public UploadCleanupProperties {
        if (graceSeconds < 0 || pollDelayMillis < 1 || leaseSeconds <= STORAGE_CALL_TIMEOUT_SECONDS
                || maxAttempts < 1 || retryBaseSeconds < 1) throw new IllegalArgumentException("上传清理配置不合法，租约须大于存储调用超时");
    }
}
