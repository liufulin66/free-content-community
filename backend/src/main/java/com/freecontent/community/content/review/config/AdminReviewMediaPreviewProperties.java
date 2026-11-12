package com.freecontent.community.content.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.admin-review-media-preview")
public record AdminReviewMediaPreviewProperties(int readUrlTtlSeconds,int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public AdminReviewMediaPreviewProperties {
        if (readUrlTtlSeconds < 1 || readUrlTtlSeconds > 604800 || ipPerMinuteLimit < 1 || userPerMinuteLimit < 1
                || redisKeyPrefix == null || redisKeyPrefix.isBlank() || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("后台审核媒体预览配置不合法");
        }
    }
}
