package com.freecontent.community.media.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.UploadCreationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class UploadCreationRateLimiter {
    private final StringRedisTemplate redis;
    private final UploadCreationProperties properties;
    private final DefaultRedisScript<List> script;

    public UploadCreationRateLimiter(StringRedisTemplate redis, UploadCreationProperties properties) {
        this.redis=redis; this.properties=properties;
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/upload-creation-rate-limit.lua"));
        script.setResultType(List.class);
    }

    public void acquire(String userId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{upload-creation}:";
        try {
            List<?> result = redis.execute(script, List.of(prefix + "user:" + TokenDigest.sha256(userId),
                    prefix + "source:" + TokenDigest.sha256(sourceIp)),
                    Integer.toString(properties.userHourlyLimit()), Integer.toString(properties.ipPerMinuteLimit()));
            if (result == null || result.size() != 2) throw unavailable();
            if (((Number) result.get(0)).longValue() == 0) {
                long wait = Math.max(1, (((Number) result.get(1)).longValue() + 999) / 1000);
                throw new BusinessException(ErrorCode.RATE_LIMITED, "上传申请过于频繁", null, wait, List.of());
            }
        } catch (DataAccessException ex) { throw unavailable(); }
    }

    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "上传限流服务暂不可用"); }
}
