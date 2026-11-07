package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.media.upload-creation")
public record UploadCreationProperties(int sessionTtlSeconds, int ipPerMinuteLimit,
        int userHourlyLimit, String redisKeyPrefix) {
    public UploadCreationProperties {
        if (sessionTtlSeconds < 1 || ipPerMinuteLimit < 1 || userHourlyLimit < 1
                || redisKeyPrefix == null || redisKeyPrefix.isBlank() || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("上传创建配置不合法");
        }
    }
}
