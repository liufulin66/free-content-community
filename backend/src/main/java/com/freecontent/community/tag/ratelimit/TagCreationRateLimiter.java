package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.tag.config.TagCreationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class TagCreationRateLimiter {
    private final StringRedisTemplate redis;
    private final TagCreationProperties properties;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();
    public TagCreationRateLimiter(StringRedisTemplate redis, TagCreationProperties properties) {
        this.redis = redis; this.properties = properties;
        script.setLocation(new ClassPathResource("redis/upload-creation-rate-limit.lua")); script.setResultType(List.class);
    }
    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{tag-create}:";
        try {
            List<?> result = redis.execute(script, List.of(prefix + "user:" + TokenDigest.sha256(actor),
                    prefix + "source:" + TokenDigest.sha256(sourceIp)),
                    Integer.toString(properties.userHourlyLimit()), Integer.toString(properties.ipPerMinuteLimit()));
            if (result == null || result.size() != 2) throw unavailable();
            if (((Number) result.get(0)).longValue() == 0) throw new BusinessException(ErrorCode.RATE_LIMITED, "标签创建过于频繁", null,
                    Math.max(1, (((Number) result.get(1)).longValue() + 999) / 1000), List.of());
        } catch (DataAccessException ex) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "标签创建限流服务暂不可用"); }
}
