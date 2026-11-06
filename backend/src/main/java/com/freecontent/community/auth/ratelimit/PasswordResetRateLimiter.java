package com.freecontent.community.auth.ratelimit;

import com.freecontent.community.auth.config.PasswordResetProperties;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PasswordResetRateLimiter {
    private final StringRedisTemplate redis;
    private final PasswordResetProperties properties;
    private final DefaultRedisScript<List> script;

    public PasswordResetRateLimiter(StringRedisTemplate redis, PasswordResetProperties properties) {
        this.redis = redis;
        this.properties = properties;
        // 复用注册的小时/分钟配额脚本，以独立键空间计数。
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/registration-rate-limit.lua"));
        script.setResultType(List.class);
    }

    public void acquire(String email, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{password-reset}:";
        try {
            List<?> result = redis.execute(script, List.of(prefix + "email:" + TokenDigest.sha256(email),
                            prefix + "source:" + TokenDigest.sha256(sourceIp)),
                    Integer.toString(properties.emailHourlyLimit()), Integer.toString(properties.ipPerMinuteLimit()));
            if (result == null || result.size() != 2) throw unavailable();
            if (((Number) result.get(0)).longValue() == 0) {
                long millis = ((Number) result.get(1)).longValue();
                throw new BusinessException(ErrorCode.RATE_LIMITED, "密码重置请求过于频繁",
                        null, Math.max(1, (millis + 999) / 1000), List.of());
            }
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "密码重置限流服务暂不可用");
    }
}
