package com.freecontent.community.content.browse.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.browse.config.PublishedMediaAccessProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PublishedMediaAccessRateLimiter {
    private final PublishedMediaAccessProperties properties;
    private final RedisRateLimitExecutor executor;

    public PublishedMediaAccessRateLimiter(StringRedisTemplate redis, PublishedMediaAccessProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String viewer, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-public-media-access}:";
        String source = prefix + "source:" + TokenDigest.sha256(sourceIp);
        if (viewer == null) {
            executor.acquire(Policy.SOURCE_MINUTE, List.of(source), "公开媒体取址过于频繁", "公开媒体取址限流服务暂不可用",
                    properties.ipPerMinuteLimit());
        } else {
            executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE, List.of(prefix + "user:" + TokenDigest.sha256(viewer), source),
                    "公开媒体取址过于频繁", "公开媒体取址限流服务暂不可用", properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
        }
    }
}
