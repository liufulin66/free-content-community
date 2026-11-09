package com.freecontent.community.tag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.tag.update")
public record TagUpdateProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public TagUpdateProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("标签修改配置不合法");
        }
    }
}
