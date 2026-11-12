package com.freecontent.community.content.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.review-submission")
public record ContentReviewSubmissionProperties(int ipPerMinuteLimit, int userHourlyLimit, String redisKeyPrefix) {
    public ContentReviewSubmissionProperties {
        if (ipPerMinuteLimit < 1 || userHourlyLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("审核提交限流配置不合法");
    }
}
