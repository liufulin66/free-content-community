package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.media.upload-query")
public record UploadQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public UploadQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("上传查询限流配置不合法");
        }
    }
}
