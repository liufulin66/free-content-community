package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.profile-update")
public record ProfileUpdateProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public ProfileUpdateProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("资料编辑限流配置不合法");
        }
    }
}
