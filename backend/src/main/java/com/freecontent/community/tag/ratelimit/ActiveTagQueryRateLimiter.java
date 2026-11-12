package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.tag.config.ActiveTagQueryProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ActiveTagQueryRateLimiter {
    private final ActiveTagQueryProperties properties;
    private final RedisRateLimitExecutor executor;

    public ActiveTagQueryRateLimiter(StringRedisTemplate redis, ActiveTagQueryProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String sourceIp) {
        String key = properties.redisKeyPrefix() + "{tag-active-query}:source:" + TokenDigest.sha256(sourceIp);
        executor.acquire(Policy.SOURCE_MINUTE, List.of(key),
                "有效标签查询过于频繁", "有效标签查询限流服务暂不可用", properties.ipPerMinuteLimit());
    }
}
