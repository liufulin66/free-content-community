package com.freecontent.community.user.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.user.config.PublicUserQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PublicUserQueryRateLimiter {
    private final PublicUserQueryProperties properties;
    private final RedisRateLimitExecutor executor;

    public PublicUserQueryRateLimiter(StringRedisTemplate redis, PublicUserQueryProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String viewerId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{user-public-query}:";
        String source = prefix + "source:" + TokenDigest.sha256(sourceIp);
        if (viewerId == null) {
            executor.acquire(Policy.SOURCE_MINUTE, List.of(source), "公开个人主页查询过于频繁",
                    "公开个人主页限流服务暂不可用", properties.ipPerMinuteLimit());
        } else {
            executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                    List.of(prefix + "user:" + TokenDigest.sha256(viewerId), source), "公开个人主页查询过于频繁",
                    "公开个人主页限流服务暂不可用", properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
        }
    }
}
