package com.freecontent.community.tag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.tag.creation")
public record TagCreationProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public TagCreationProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("标签创建配置不合法");
    }
}
