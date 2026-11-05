package com.freecontent.community.auth.ratelimit;

import com.freecontent.community.auth.config.EmailChallengeProperties;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Component
public class EmailChallengeRateLimiter {

    private final StringRedisTemplate redis;
    private final EmailChallengeProperties properties;
    private final DefaultRedisScript<List> script;

    public EmailChallengeRateLimiter(StringRedisTemplate redis, EmailChallengeProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/email-challenge-rate-limit.lua"));
        script.setResultType(List.class);
    }

    /**
     * 原子检查三个配额。返回Redis受理时刻，避免较早申请在延迟后覆盖较新申请。
     * 计数限制申请次数，数据库失败不会自动退还配额。
     */
    public Instant acquire(String email, EmailChallengePurpose purpose, String sourceIp) {
        String prefix = properties.redisKeyPrefix() + "{email-challenge}:";
        String emailHash = hash(email);
        List<String> keys = List.of(
                prefix + "cooldown:" + purpose.name() + ":" + emailHash,
                prefix + "email:" + emailHash,
                prefix + "source:" + hash(sourceIp));
        try {
            List<?> result = redis.execute(script, keys,
                    Long.toString(properties.resendIntervalSeconds() * 1000),
                    Integer.toString(properties.emailHourlyLimit()),
                    Integer.toString(properties.ipHourlyLimit()));
            if (result == null || result.size() != 3) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "验证码限流服务暂不可用");
            }
            if (((Number) result.get(0)).longValue() == 0) {
                long retryMillis = ((Number) result.get(1)).longValue();
                throw new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试",
                        null, Math.max(1, (retryMillis + 999) / 1000), List.of());
            }
            return Instant.ofEpochSecond(((Number) result.get(1)).longValue(),
                    ((Number) result.get(2)).longValue() * 1000);
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "验证码限流服务暂不可用");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
