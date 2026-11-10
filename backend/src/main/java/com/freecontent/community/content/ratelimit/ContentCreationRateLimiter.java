package com.freecontent.community.content.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.config.ContentCreationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ContentCreationRateLimiter {
    private final StringRedisTemplate redis;
    private final ContentCreationProperties properties;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    public ContentCreationRateLimiter(StringRedisTemplate redis, ContentCreationProperties properties) {
        this.redis = redis; this.properties = properties;
        script.setLocation(new ClassPathResource("redis/upload-creation-rate-limit.lua")); script.setResultType(List.class);
    }

    public void acquire(String actor, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{content-create}:";
        try {
            List<?> result = redis.execute(script, List.of(prefix + "user:" + TokenDigest.sha256(actor), prefix + "source:" + TokenDigest.sha256(sourceIp)),
                    Integer.toString(properties.userHourlyLimit()), Integer.toString(properties.ipPerMinuteLimit()));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Number decision)
                    || !(result.get(1) instanceof Number waitMillis) || (decision.longValue() != 0 && decision.longValue() != 1)) throw unavailable();
            if (decision.longValue() == 0) throw new BusinessException(ErrorCode.RATE_LIMITED, "内容创建过于频繁", null,
                    Math.max(1, (waitMillis.longValue() + 999) / 1000), List.of());
        } catch (DataAccessException ex) { throw unavailable(); }
    }

    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容创建限流服务暂不可用"); }
}
