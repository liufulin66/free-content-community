package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.UploadCreationProperties;
import com.freecontent.community.media.ratelimit.UploadCreationRateLimiter;
import com.freecontent.community.tag.config.TagCreationProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class TagCreationRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:tag-create-limit:" + UUID.randomUUID() + ":";
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(prefix + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
    @Test void atomicHourlyUserAndMinuteSourceQuotasAreIndependentFromUploads() {
        var limiter = new TagCreationRateLimiter(redis, new TagCreationProperties(3, 2, prefix));
        limiter.acquire("admin-a", "source"); limiter.acquire("admin-a", "source");
        var limited = assertThrows(BusinessException.class, () -> limiter.acquire("admin-a", "source"));
        assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode()); assertTrue(limited.retryAfterSeconds() > 3500);
        limiter.acquire("admin-b", "source");
        assertEquals(ErrorCode.RATE_LIMITED, assertThrows(BusinessException.class, () -> limiter.acquire("admin-c", "source")).errorCode());
        String userKey = prefix + "{tag-create}:user:" + TokenDigest.sha256("admin-a");
        String sourceKey = prefix + "{tag-create}:source:" + TokenDigest.sha256("source");
        assertTrue(redis.getExpire(userKey, TimeUnit.SECONDS) > 3500);
        assertTrue(redis.getExpire(sourceKey, TimeUnit.SECONDS) > 0 && redis.getExpire(sourceKey, TimeUnit.SECONDS) <= 60);
        new UploadCreationRateLimiter(redis, new UploadCreationProperties(7200, 3, 2, prefix)).acquire("admin-a", "source");
    }
}
