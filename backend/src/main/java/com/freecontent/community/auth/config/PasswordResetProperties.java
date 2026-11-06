package com.freecontent.community.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth.password-reset")
public record PasswordResetProperties(int maxCodeFailures, int ipPerMinuteLimit,
                                      int emailHourlyLimit, String redisKeyPrefix) {
    public PasswordResetProperties {
        if (maxCodeFailures < 1 || maxCodeFailures > 20 || ipPerMinuteLimit < 1 || emailHourlyLimit < 1
                || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("密码重置限流配置不合法");
        }
    }
}
