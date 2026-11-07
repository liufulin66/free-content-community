package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.media.asset-query")
public record MediaAssetQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public MediaAssetQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("素材查询配置不合法");
    }
}
