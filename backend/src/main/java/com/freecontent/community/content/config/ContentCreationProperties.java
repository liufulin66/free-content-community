package com.freecontent.community.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.creation")
public record ContentCreationProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public ContentCreationProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("内容创建限流配置不合法");
    }
}
