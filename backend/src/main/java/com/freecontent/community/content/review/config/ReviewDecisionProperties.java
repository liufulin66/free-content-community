package com.freecontent.community.content.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.review-decision")
public record ReviewDecisionProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public ReviewDecisionProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("审核决定限流配置不合法");
    }
}
