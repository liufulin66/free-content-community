package com.freecontent.community.media.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.media.config.UploadCreationProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UploadCreationRateLimiter {
    private final UploadCreationProperties properties;
    private final RedisRateLimitExecutor executor;

    public UploadCreationRateLimiter(StringRedisTemplate redis, UploadCreationProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{upload-creation}:";
        executor.acquire(Policy.USER_HOUR_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "上传申请过于频繁", "上传限流服务暂不可用",
                properties.userHourlyLimit(), properties.ipPerMinuteLimit());
    }
}
