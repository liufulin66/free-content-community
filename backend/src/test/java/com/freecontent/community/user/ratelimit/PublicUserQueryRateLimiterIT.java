package com.freecontent.community.user.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.config.PublicContentQueryProperties;
import com.freecontent.community.content.browse.ratelimit.PublicContentQueryRateLimiter;
import com.freecontent.community.user.config.PublicUserQueryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class PublicUserQueryRateLimiterIT {
    @Autowired private StringRedisTemplate redis;

    @Test
    void visitorsShareTheProfileSourceBudgetWhileContentBrowsingRemainsIndependent() {
        String prefix = "test:public-user-budget:" + UUID.randomUUID() + ":";
        try {
            var limiter = new PublicUserQueryRateLimiter(redis, new PublicUserQueryProperties(3, 2, prefix));
            limiter.acquire(null, "source");
            limiter.acquire("viewer", "source");
            limiter.acquire("viewer", "source");
            var sourceError = assertThrows(BusinessException.class, () -> limiter.acquire(null, "source"));
            assertEquals(ErrorCode.RATE_LIMITED, sourceError.errorCode());
            assertTrue(sourceError.retryAfterSeconds() > 0);
            assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class,
                    () -> limiter.acquire("viewer", "other-source")).errorCode());
            assertNull(redis.opsForValue().get(prefix + "{user-public-query}:source:" + TokenDigest.sha256("other-source")));
            new PublicContentQueryRateLimiter(redis, new PublicContentQueryProperties(3, 2, prefix)).acquire("viewer", "source");
            assertEquals("3", redis.opsForValue().get(prefix + "{user-public-query}:source:" + TokenDigest.sha256("source")));
        } finally {
            clear(prefix);
        }
    }

    @Test
    void concurrentAnonymousVisitorsCannotOverspendTheSourceWindow() throws Exception {
        String prefix = "test:public-user-concurrent:" + UUID.randomUUID() + ":";
        var pool = Executors.newFixedThreadPool(8);
        try {
            var limiter = new PublicUserQueryRateLimiter(redis, new PublicUserQueryProperties(5, 3, prefix));
            var start = new CountDownLatch(1);
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    try {
                        limiter.acquire(null, "source");
                        return true;
                    } catch (BusinessException failure) {
                        assertEquals(ErrorCode.RATE_LIMITED, failure.errorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(5, accepted);
            assertEquals("5", redis.opsForValue().get(prefix + "{user-public-query}:source:" + TokenDigest.sha256("source")));
        } finally {
            pool.shutdownNow();
            clear(prefix);
        }
    }

    private void clear(String prefix) {
        var keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
