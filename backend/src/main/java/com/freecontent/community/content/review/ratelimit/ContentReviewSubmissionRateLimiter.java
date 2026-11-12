package com.freecontent.community.content.review.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.content.review.config.ContentReviewSubmissionProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ContentReviewSubmissionRateLimiter {
    private final ContentReviewSubmissionProperties properties;
    private final RedisRateLimitExecutor executor;

    public ContentReviewSubmissionRateLimiter(StringRedisTemplate redis, ContentReviewSubmissionProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-review-submission}:";
        executor.acquire(Policy.USER_HOUR_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "审核提交过于频繁", "审核提交限流服务暂不可用",
                properties.userHourlyLimit(), properties.ipPerMinuteLimit());
    }
}
