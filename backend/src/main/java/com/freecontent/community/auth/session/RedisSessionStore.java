package com.freecontent.community.auth.session;

import com.freecontent.community.auth.config.LoginProperties;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class RedisSessionStore {
    private final StringRedisTemplate redis;
    private final LoginProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final DefaultRedisScript<Long> create;
    private final DefaultRedisScript<List> read;

    public RedisSessionStore(StringRedisTemplate redis, LoginProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        create = new DefaultRedisScript<>();
        create.setLocation(new ClassPathResource("redis/session-create.lua"));
        create.setResultType(Long.class);
        read = new DefaultRedisScript<>();
        read.setLocation(new ClassPathResource("redis/session-read.lua"));
        read.setResultType(List.class);
    }

    public IssuedSession create(String userId, long authVersion, String deviceLabel) {
        Instant now = Instant.ofEpochMilli(clock.millis());
        StoredSession session = new StoredSession(UUID.randomUUID().toString().replace("-", ""),
                userId, authVersion, now, now.plusSeconds(properties.ttlSeconds()), deviceLabel);
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String digest = TokenDigest.sha256(token);
            try {
                Long result = redis.execute(create,
                        List.of(prefix() + "user:" + userId, prefix() + "token:" + digest),
                        prefix() + "token:", digest, session.sessionId(), userId, Long.toString(authVersion),
                        Long.toString(now.toEpochMilli()), Long.toString(session.expiresAt().toEpochMilli()),
                        deviceLabel == null ? "" : deviceLabel, Integer.toString(properties.maxSessions()));
                if (result == null) throw unavailable();
                if (result == 1) return new IssuedSession(token, session);
                if (result == -1) throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "邮箱或密码不正确");
                if (result != 0) throw unavailable();
            } catch (DataAccessException ex) {
                // 写入结果未知时不签发Token，不将依赖故障伪装为凭据错误。
                throw unavailable();
            }
        }
        throw unavailable();
    }

    public StoredSession find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            String digest = TokenDigest.sha256(token);
            List<?> values = redis.execute(read, List.of(prefix() + "token:" + digest),
                    Long.toString(clock.millis()), prefix() + "user:", digest);
            if (values == null) throw unavailable();
            if (values.isEmpty()) return null;
            if (values.size() != 6) throw unavailable();
            return new StoredSession((String) values.get(0), (String) values.get(1),
                    Long.parseLong((String) values.get(2)),
                    Instant.ofEpochMilli(Long.parseLong((String) values.get(3))),
                    Instant.ofEpochMilli(Long.parseLong((String) values.get(4))), (String) values.get(5));
        } catch (DataAccessException | IllegalArgumentException ex) {
            throw unavailable();
        }
    }

    private String prefix() { return properties.redisKeyPrefix() + "{sessions}:"; }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "登录会话服务暂不可用");
    }
}
