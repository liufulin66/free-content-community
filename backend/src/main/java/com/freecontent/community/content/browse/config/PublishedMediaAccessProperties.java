package com.freecontent.community.content.browse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.public-media-access")
public record PublishedMediaAccessProperties(int readUrlTtlSeconds, int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public PublishedMediaAccessProperties {
        if (readUrlTtlSeconds < 1 || readUrlTtlSeconds > 604800 || ipPerMinuteLimit < 1 || userPerMinuteLimit < 1
                || redisKeyPrefix == null || redisKeyPrefix.isBlank() || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("公开媒体取址配置不合法");
        }
    }
}
