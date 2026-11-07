package com.freecontent.community.media.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.MediaTaskQueryProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MediaTaskQueryRateLimiter {
    private final StringRedisTemplate redis;
    private final MediaTaskQueryProperties properties;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();
    public MediaTaskQueryRateLimiter(StringRedisTemplate redis, MediaTaskQueryProperties properties) {
        this.redis = redis; this.properties = properties;
        script.setLocation(new ClassPathResource("redis/upload-query-rate-limit.lua")); script.setResultType(List.class);
    }
    public void acquire(String owner, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{media-task-query}:";
        try {
            List<?> result = redis.execute(script, List.of(prefix + "user:" + TokenDigest.sha256(owner),
                    prefix + "source:" + TokenDigest.sha256(sourceIp)),
                    Integer.toString(properties.userPerMinuteLimit()), Integer.toString(properties.ipPerMinuteLimit()));
            if (result == null || result.size() != 2) throw unavailable();
            if (((Number) result.get(0)).longValue() == 0) throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "媒体任务查询过于频繁", null, Math.max(1, (((Number) result.get(1)).longValue() + 999) / 1000), List.of());
        } catch (DataAccessException ex) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "媒体任务查询限流服务暂不可用"); }
}
