package com.freecontent.community.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth.login")
public record LoginProperties(int ttlSeconds, int maxSessions, int ipPerMinuteLimit,
                              int emailWindowLimit, int emailWindowSeconds, String redisKeyPrefix) {
    public LoginProperties {
        if (ttlSeconds < 1 || maxSessions < 1 || maxSessions > 100
                || ipPerMinuteLimit < 1 || emailWindowLimit < 1 || emailWindowSeconds < 1
                || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("登录与会话配置不合法");
        }
    }
}
