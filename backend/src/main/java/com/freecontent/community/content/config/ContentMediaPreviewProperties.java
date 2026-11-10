package com.freecontent.community.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.media-preview")
public record ContentMediaPreviewProperties(int readUrlTtlSeconds,int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public ContentMediaPreviewProperties {
        if (readUrlTtlSeconds < 1 || readUrlTtlSeconds > 604800 || ipPerMinuteLimit < 1 || userPerMinuteLimit < 1
                || redisKeyPrefix == null || redisKeyPrefix.isBlank() || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("媒体预览配置不合法");
        }
    }
}
