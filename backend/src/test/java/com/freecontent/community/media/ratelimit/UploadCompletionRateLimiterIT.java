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
class UploadCompletionRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:completion-limit:" + UUID.randomUUID() + ":";
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(prefix + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test void completionChecksBothQuotasAtomicallyAndIsIsolatedFromOtherOperations() {
        var completion = new UploadCompletionRateLimiter(redis, new UploadCompletionProperties(3, 2, prefix, 2));
        completion.acquire("user-a", "source"); completion.acquire("user-a", "source");
        var limited = assertThrows(BusinessException.class, () -> completion.acquire("user-a", "source"));
        assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode()); assertTrue(limited.retryAfterSeconds() >= 1);
        completion.acquire("user-b", "source");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> completion.acquire("user-c", "source")).errorCode());
        new MediaTaskQueryRateLimiter(redis, new MediaTaskQueryProperties(3, 2, prefix)).acquire("user-a", "source");
        new UploadQueryRateLimiter(redis, new UploadQueryProperties(3, 2, prefix)).acquire("user-a", "source");
        new UploadCancellationRateLimiter(redis, new UploadCancellationProperties(3, 2, prefix)).acquire("user-a", "source");
    }

    @Test void taskQueryHasItsOwnBoundedUserAndSourceCounters() {
        var query = new MediaTaskQueryRateLimiter(redis, new MediaTaskQueryProperties(2, 1, prefix));
        query.acquire("user-a", "source");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> query.acquire("user-a", "source")).errorCode());
        query.acquire("user-b", "source");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> query.acquire("user-c", "source")).errorCode());
    }
}
