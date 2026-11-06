package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.password-change")
public record PasswordChangeProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public PasswordChangeProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("修改密码限流配置不合法");
        }
    }
}
