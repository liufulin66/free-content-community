package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.media.upload-cancel")
public record UploadCancellationProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public UploadCancellationProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("上传取消限流配置不合法");
    }
}
