package com.freecontent.community.user.ratelimit;

import com.freecontent.community.user.config.PasswordChangeProperties;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PasswordChangeRateLimiter {
    private final StringRedisTemplate redis;
    private final PasswordChangeProperties properties;
    private final DefaultRedisScript<List> script;

    public PasswordChangeRateLimiter(StringRedisTemplate redis, PasswordChangeProperties properties) {
        this.redis = redis;
        this.properties = properties;
        // 复用小时/分钟配额脚本，第一维按已认证用户ID独立计数。
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/registration-rate-limit.lua"));
        script.setResultType(List.class);
    }

    public void acquire(String userId, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{password-change}:";
        try {
            List<?> result = redis.execute(script, List.of(prefix + "user:" + TokenDigest.sha256(userId),
                            prefix + "source:" + TokenDigest.sha256(sourceIp)),
                    Integer.toString(properties.userHourlyLimit()), Integer.toString(properties.ipPerMinuteLimit()));
            if (result == null || result.size() != 2) throw unavailable();
            if (((Number) result.get(0)).longValue() == 0) {
                long millis = ((Number) result.get(1)).longValue();
                throw new BusinessException(ErrorCode.RATE_LIMITED, "修改密码请求过于频繁",
                        null, Math.max(1, (millis + 999) / 1000), List.of());
            }
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "修改密码限流服务暂不可用");
    }
}
