package com.freecontent.community.content.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.review-cancellation")
public record ReviewCancellationProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public ReviewCancellationProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("审核撤销限流配置不合法");
    }
}
