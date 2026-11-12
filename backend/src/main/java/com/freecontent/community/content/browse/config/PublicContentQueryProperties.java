package com.freecontent.community.content.browse.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.content.public-query")
public record PublicContentQueryProperties(int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public PublicContentQueryProperties {
        if(ipPerMinuteLimit<1||userPerMinuteLimit<1||redisKeyPrefix==null||redisKeyPrefix.isBlank()||redisKeyPrefix.contains("{")||redisKeyPrefix.contains("}"))
            throw new IllegalArgumentException("公开内容查询限流配置不合法");
    }
}
