package com.freecontent.community.content.review.ratelimit;

import com.freecontent.community.content.ratelimit.ContentQueryRateLimiter;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.config.AdminReviewMediaPreviewProperties;
import com.freecontent.community.content.config.ContentQueryProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false","app.media.upload-cleanup.enabled=false"})
class AdminReviewMediaPreviewRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:admin-review-preview-limit:"+UUID.randomUUID()+":";
    @AfterEach void cleanup() { Set<String> keys = redis.keys(prefix+"*"); if (keys != null && !keys.isEmpty()) redis.delete(keys); }
    @Test void atomicSourceAndUserMinuteLimitsAreIndependentFromContentQueries() throws Exception {
        var limiter = new AdminReviewMediaPreviewRateLimiter(redis,new AdminReviewMediaPreviewProperties(300,3,2,prefix));
        var workers = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) results.add(workers.submit(() -> {
                start.await(); try { limiter.acquire("user","source"); return true; }
                catch (BusinessException ex) { assertEquals(ErrorCode.RATE_LIMITED,ex.errorCode()); return false; }
            }));
            start.countDown(); int accepted = 0;
            for (var result : results) if (result.get(10,TimeUnit.SECONDS)) accepted++;
            assertEquals(2,accepted);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS)); }
        String user = prefix+"{content-admin-review-media-preview}:user:"+TokenDigest.sha256("user"),source = prefix+"{content-admin-review-media-preview}:source:"+TokenDigest.sha256("source");
        assertEquals("2",redis.opsForValue().get(user)); assertEquals("2",redis.opsForValue().get(source));
        assertTrue(redis.getExpire(user) > 0 && redis.getExpire(user) <= 60); assertTrue(redis.getExpire(source) > 0 && redis.getExpire(source) <= 60);
        limiter.acquire("other","source"); assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,() -> limiter.acquire("third","source")).errorCode());
        new ContentQueryRateLimiter(redis,new ContentQueryProperties(120,60,prefix)).acquire("user","source");
    }
}
