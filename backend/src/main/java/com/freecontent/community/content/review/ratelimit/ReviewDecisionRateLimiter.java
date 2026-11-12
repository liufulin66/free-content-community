package com.freecontent.community.content.review.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.review.config.ReviewDecisionProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReviewDecisionRateLimiter {
    private final ReviewDecisionProperties properties;
    private final RedisRateLimitExecutor executor;

    public ReviewDecisionRateLimiter(StringRedisTemplate redis, ReviewDecisionProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-review-decision}:";
        executor.acquire(Policy.USER_HOUR_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "审核决定过于频繁", "审核决定限流服务暂不可用",
                properties.userHourlyLimit(), properties.ipPerMinuteLimit());
    }
}
