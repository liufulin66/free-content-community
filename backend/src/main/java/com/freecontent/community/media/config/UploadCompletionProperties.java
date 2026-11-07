package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.media.upload-completion")
public record UploadCompletionProperties(int ipPerMinuteLimit, int userPerMinuteLimit,
                                         String redisKeyPrefix, int retryAfterSeconds) {
    public UploadCompletionProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || retryAfterSeconds < 1
                || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("上传完成配置不合法");
        }
    }
}
