package com.freecontent.community.content.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.draft-save")
public record ContentDraftSaveProperties(int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public ContentDraftSaveProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("草稿保存限流配置不合法");
    }
}
