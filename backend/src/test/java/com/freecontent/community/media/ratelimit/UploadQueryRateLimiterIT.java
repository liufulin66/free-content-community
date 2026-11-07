package com.freecontent.community.media.ratelimit;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.UploadQueryProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "app.auth.email-challenge.delivery-enabled=false")
class UploadQueryRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:upload-query-limit:" + UUID.randomUUID() + ":";

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void checksBothMinuteQuotasBeforeConsumingEitherQuota() {
        var limiter = new UploadQueryRateLimiter(redis, new UploadQueryProperties(3, 2, prefix));
        limiter.acquire("user-a", "source-a");
        limiter.acquire("user-a", "source-a");
        BusinessException userLimit = assertThrows(BusinessException.class, () -> limiter.acquire("user-a", "source-a"));
        assertEquals(ErrorCode.RATE_LIMITED, userLimit.errorCode());
        assertTrue(userLimit.retryAfterSeconds() >= 1 && userLimit.retryAfterSeconds() <= 60);
        limiter.acquire("user-b", "source-a");
        BusinessException sourceLimit = assertThrows(BusinessException.class, () -> limiter.acquire("user-c", "source-a"));
        assertEquals(ErrorCode.RATE_LIMITED, sourceLimit.errorCode());
    }

    @Test
    void changingSourceDoesNotBypassTheUserMinuteQuota() {
        var limiter = new UploadQueryRateLimiter(redis, new UploadQueryProperties(3, 2, prefix));
        limiter.acquire("same-user", "source-a");
        limiter.acquire("same-user", "source-b");
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(BusinessException.class, () -> limiter.acquire("same-user", "source-c")).errorCode());
    }
}
