package com.freecontent.community.content.browse.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.browse.config.PublicContentQueryProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PublicContentQueryRateLimiter {
    private final PublicContentQueryProperties properties;
    private final RedisRateLimitExecutor executor;

    public PublicContentQueryRateLimiter(StringRedisTemplate redis, PublicContentQueryProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String viewer, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-public-query}:";
        String source = prefix + "source:" + TokenDigest.sha256(sourceIp);
        if (viewer == null) {
            executor.acquire(Policy.SOURCE_MINUTE, List.of(source), "公开内容查询过于频繁", "公开查询限流服务暂不可用",
                    properties.ipPerMinuteLimit());
        } else {
            executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                    List.of(prefix + "user:" + TokenDigest.sha256(viewer), source),
                    "公开内容查询过于频繁", "公开查询限流服务暂不可用",
                    properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
        }
    }
}
