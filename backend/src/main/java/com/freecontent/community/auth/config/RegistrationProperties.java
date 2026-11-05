package com.freecontent.community.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth.registration")
public record RegistrationProperties(
        int maxCodeFailures,
        int ipPerMinuteLimit,
        int emailHourlyLimit,
        int argon2MemoryKib,
        int argon2Iterations,
        int argon2Parallelism,
        int passwordHashConcurrency,
        String redisKeyPrefix
) {
    public RegistrationProperties {
        if (maxCodeFailures < 1 || maxCodeFailures > 20
                || ipPerMinuteLimit < 1 || emailHourlyLimit < 1
                || argon2MemoryKib < 19456 || argon2MemoryKib > 262144
                || argon2Iterations < 2 || argon2Iterations > 10
                || argon2Parallelism < 1 || argon2Parallelism > 4
                || passwordHashConcurrency < 1 || passwordHashConcurrency > 8
                || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("注册限流或密码哈希配置不合法");
        }
    }
}
