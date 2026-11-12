package com.freecontent.community.content.review.ratelimit;

import com.freecontent.community.content.workspace.ratelimit.ContentCreationRateLimiter;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.config.ReviewCancellationProperties;
import com.freecontent.community.content.workspace.config.ContentCreationProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ReviewCancellationRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:review-cancellation-limit:" + UUID.randomUUID() + ":";
    @AfterEach void cleanup() { Set<String> keys = redis.keys(prefix + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys); }
    @Test void concurrentQueriesRespectBothMinuteWindowsAndStayIndependentFromCreation() throws Exception {
        var limiter = new ReviewCancellationRateLimiter(redis, new ReviewCancellationProperties(3, 2, prefix));
        var workers = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) results.add(workers.submit(() -> {
                start.await();
                try { limiter.acquire("user", "source"); return true; }
                catch (BusinessException ex) { assertEquals(ErrorCode.RATE_LIMITED, ex.errorCode()); return false; }
            }));
            start.countDown(); int accepted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(2, accepted);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        String userKey = prefix + "{content-review-cancellation}:user:" + TokenDigest.sha256("user");
        String sourceKey = prefix + "{content-review-cancellation}:source:" + TokenDigest.sha256("source");
        assertEquals("2", redis.opsForValue().get(userKey)); assertEquals("2", redis.opsForValue().get(sourceKey));
        assertTrue(redis.getExpire(userKey) > 0 && redis.getExpire(userKey) <= 60);
        assertTrue(redis.getExpire(sourceKey) > 0 && redis.getExpire(sourceKey) <= 60);
        limiter.acquire("other", "source");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> limiter.acquire("third", "source")).errorCode());
        new ContentCreationRateLimiter(redis, new ContentCreationProperties(60,100,prefix)).acquire("user", "source");
    }
}
