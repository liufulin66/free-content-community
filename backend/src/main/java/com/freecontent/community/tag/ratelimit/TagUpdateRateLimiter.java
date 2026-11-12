package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.tag.config.TagUpdateProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TagUpdateRateLimiter {
    private final TagUpdateProperties properties;
    private final RedisRateLimitExecutor executor;

    public TagUpdateRateLimiter(StringRedisTemplate redis, TagUpdateProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{tag-update}:";
        executor.acquire(Policy.USER_HOUR_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "标签修改过于频繁", "标签修改限流服务暂不可用",
                properties.userHourlyLimit(), properties.ipPerMinuteLimit());
    }
}
