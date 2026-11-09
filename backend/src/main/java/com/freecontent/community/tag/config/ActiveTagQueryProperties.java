package com.freecontent.community.tag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.tag.active-query")
public record ActiveTagQueryProperties(int ipPerMinuteLimit, String redisKeyPrefix) {
    public ActiveTagQueryProperties {
        if (ipPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("有效标签查询配置不合法");
        }
    }
}
