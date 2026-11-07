package com.freecontent.community.media.ratelimit;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class MediaAssetQueryRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:asset-query-limit:" + UUID.randomUUID() + ":";
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(prefix + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test void checksBothMinuteCountersAtomicallyAndIsolatesOtherQueries() {
        var limiter = new MediaAssetQueryRateLimiter(redis, new MediaAssetQueryProperties(3, 2, prefix));
        limiter.acquire("user-a", "source"); limiter.acquire("user-a", "source");
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("user-a", "source"));
        assertEquals(ErrorCode.RATE_LIMITED, failure.errorCode()); assertTrue(failure.retryAfterSeconds() >= 1);
        limiter.acquire("user-b", "source");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> limiter.acquire("user-c", "source")).errorCode());
        new MediaTaskQueryRateLimiter(redis, new MediaTaskQueryProperties(3, 2, prefix)).acquire("user-a", "source");
        new UploadQueryRateLimiter(redis, new UploadQueryProperties(3, 2, prefix)).acquire("user-a", "source");
        new UploadCompletionRateLimiter(redis, new UploadCompletionProperties(3, 2, prefix, 2)).acquire("user-a", "source");
    }
}
