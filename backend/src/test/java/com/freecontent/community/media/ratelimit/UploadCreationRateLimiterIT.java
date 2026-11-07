package com.freecontent.community.media.ratelimit;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.UploadCreationProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "app.auth.email-challenge.delivery-enabled=false")
class UploadCreationRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:upload-limit:" + UUID.randomUUID() + ":";

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void rejectedUserAttemptDoesNotConsumeSourceQuota() {
        var limiter = new UploadCreationRateLimiter(redis, new UploadCreationProperties(7200, 3, 2, prefix));
        limiter.acquire("user-a", "source-a");
        limiter.acquire("user-a", "source-a");
        BusinessException hourly = assertThrows(BusinessException.class, () -> limiter.acquire("user-a", "source-a"));
        assertEquals(ErrorCode.RATE_LIMITED, hourly.errorCode());
        assertTrue(hourly.retryAfterSeconds() > 3500);
        limiter.acquire("user-b", "source-a");
        BusinessException minute = assertThrows(BusinessException.class, () -> limiter.acquire("user-c", "source-a"));
        assertEquals(ErrorCode.RATE_LIMITED, minute.errorCode());
        assertTrue(minute.retryAfterSeconds() >= 1 && minute.retryAfterSeconds() <= 60);
    }

    @Test
    void switchingSourcesCannotBypassUserLimit() {
        var limiter = new UploadCreationRateLimiter(redis, new UploadCreationProperties(7200, 3, 2, prefix));
        limiter.acquire("same-user", "source-a");
        limiter.acquire("same-user", "source-b");
        BusinessException failure = assertThrows(BusinessException.class, () -> limiter.acquire("same-user", "source-c"));
        assertEquals(ErrorCode.RATE_LIMITED, failure.errorCode());
    }
}
