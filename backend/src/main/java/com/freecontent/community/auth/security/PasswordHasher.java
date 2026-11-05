package com.freecontent.community.auth.security;

import com.freecontent.community.auth.config.RegistrationProperties;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Semaphore;

/** 限制高成本密码哈希的并发数；编码在数据库事务之外进行。 */
@Component
public class PasswordHasher {
    private final PasswordEncoder encoder;
    private final Semaphore permits;

    public PasswordHasher(PasswordEncoder encoder, RegistrationProperties properties) {
        this.encoder = encoder;
        this.permits = new Semaphore(properties.passwordHashConcurrency());
    }

    public String encode(String rawPassword) {
        if (!permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "注册服务繁忙，请稍后重试",
                    null, 1L, List.of());
        }
        try {
            return encoder.encode(rawPassword);
        } finally {
            permits.release();
        }
    }
}
