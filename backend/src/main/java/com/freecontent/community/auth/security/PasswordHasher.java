package com.freecontent.community.auth.security;

import com.freecontent.community.auth.config.RegistrationProperties;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 限制高成本密码哈希的并发数；编码在数据库事务之外进行。 */
@Component
public class PasswordHasher {
    private final PasswordEncoder encoder;
    private final Semaphore permits;
    private final String dummyHash;

    public PasswordHasher(PasswordEncoder encoder, RegistrationProperties properties) {
        this.encoder = encoder;
        this.permits = new Semaphore(properties.passwordHashConcurrency());
        // 启动时生成一次，未知邮箱的请求只做matches，不逐次生成哈希。
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public String encode(String rawPassword) {
        return compute(() -> encoder.encode(rawPassword));
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        return compute(() -> encoder.matches(rawPassword, encodedPassword));
    }

    public void verifyMissingAccount(String rawPassword) {
        matches(rawPassword, dummyHash);
    }

    private <T> T compute(Supplier<T> operation) {
        if (!permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "密码校验服务繁忙，请稍后重试",
                    null, 1L, List.of());
        }
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }
}
