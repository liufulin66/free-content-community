package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.tag.config.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class ActiveTagQueryRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:active-tags-limit:" + UUID.randomUUID() + ":";
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void sourcesAreIndependentAndDoNotConsumeAnyAdminTagQuota() {
        var limiter = new ActiveTagQueryRateLimiter(redis, new ActiveTagQueryProperties(2, prefix));
        limiter.acquire("source-a"); limiter.acquire("source-a");
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("source-a"));
        assertEquals(ErrorCode.RATE_LIMITED, failure.errorCode());
        assertTrue(failure.retryAfterSeconds() >= 1 && failure.retryAfterSeconds() <= 60);
        long ttl = redis.getExpire(prefix + "{tag-active-query}:source:" + TokenDigest.sha256("source-a"), TimeUnit.SECONDS);
        assertTrue(ttl >= 1 && ttl <= 60);
        limiter.acquire("source-b");
        new TagCreationRateLimiter(redis, new TagCreationProperties(2, 2, prefix)).acquire("admin", "source-a");
        new TagQueryRateLimiter(redis, new TagQueryProperties(2, 2, prefix)).acquire("admin", "source-a");
        new TagUpdateRateLimiter(redis, new TagUpdateProperties(2, 2, prefix)).acquire("admin", "source-a");
    }

    @Test
    void concurrentRequestsCannotExceedTheSourceAllowance() throws Exception {
        var limiter = new ActiveTagQueryRateLimiter(redis, new ActiveTagQueryProperties(3, prefix));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            var results = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) results.add(workers.submit(() -> {
                try { limiter.acquire("source"); return true; }
                catch (BusinessException ex) { assertEquals(ErrorCode.RATE_LIMITED, ex.errorCode()); return false; }
            }));
            int allowed = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) allowed++;
            assertEquals(3, allowed);
        } finally { workers.shutdownNow(); }
    }
}
