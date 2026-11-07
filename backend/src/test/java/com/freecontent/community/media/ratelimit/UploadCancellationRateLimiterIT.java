package com.freecontent.community.media.ratelimit;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.UploadCancellationProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class UploadCancellationRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:cancel-limit:" + UUID.randomUUID() + ":";
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
    @Test void checksBothMinuteQuotasBeforeIncrementingEitherCounter() {
        var limiter = new UploadCancellationRateLimiter(redis, new UploadCancellationProperties(3, 2, prefix));
        limiter.acquire("user-a", "source"); limiter.acquire("user-a", "source");
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("user-a", "source"));
        assertEquals(ErrorCode.RATE_LIMITED, failure.errorCode());
        assertTrue(failure.retryAfterSeconds() >= 1 && failure.retryAfterSeconds() <= 60);
        limiter.acquire("user-b", "source");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> limiter.acquire("user-c", "source")).errorCode());
    }
}
