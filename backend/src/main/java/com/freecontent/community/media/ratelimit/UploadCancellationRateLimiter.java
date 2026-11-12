package com.freecontent.community.media.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor;
import com.freecontent.community.common.ratelimit.RedisRateLimitExecutor.Policy;
import com.freecontent.community.media.config.UploadCancellationProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UploadCancellationRateLimiter {
    private final UploadCancellationProperties properties;
    private final RedisRateLimitExecutor executor;

    public UploadCancellationRateLimiter(StringRedisTemplate redis, UploadCancellationProperties properties) {
        this.properties = properties;
        this.executor = new RedisRateLimitExecutor(redis);
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{upload-cancel}:";
        executor.acquire(Policy.USER_MINUTE_SOURCE_MINUTE,
                List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                "上传取消过于频繁", "上传取消限流服务暂不可用",
                properties.userPerMinuteLimit(), properties.ipPerMinuteLimit());
    }
}
