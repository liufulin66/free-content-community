package com.freecontent.community.tag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.tag.query")
public record TagQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public TagQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("标签查询配置不合法");
        }
    }
}
