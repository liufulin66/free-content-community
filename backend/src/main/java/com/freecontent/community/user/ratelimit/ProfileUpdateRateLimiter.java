package com.freecontent.community.user.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.config.ProfileUpdateProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ProfileUpdateRateLimiter {
    private final StringRedisTemplate redis;
    private final ProfileUpdateProperties properties;
    private final DefaultRedisScript<List> script;

    public ProfileUpdateRateLimiter(StringRedisTemplate redis, ProfileUpdateProperties properties) {
        this.redis = redis;
        this.properties = properties;
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/registration-rate-limit.lua"));
        script.setResultType(List.class);
    }

    public void acquire(String userId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{profile-update}:";
        try {
            List<?> result = redis.execute(script, List.of(prefix + "user:" + TokenDigest.sha256(userId),
                            prefix + "source:" + TokenDigest.sha256(sourceIp)),
                    Integer.toString(properties.userHourlyLimit()), Integer.toString(properties.ipPerMinuteLimit()));
            if (result == null || result.size() != 2) throw unavailable();
            if (((Number) result.get(0)).longValue() == 0) {
                long millis = ((Number) result.get(1)).longValue();
                throw new BusinessException(ErrorCode.RATE_LIMITED, "资料修改请求过于频繁",
                        null, Math.max(1, (millis + 999) / 1000), List.of());
            }
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "资料修改限流服务暂不可用");
    }
}
