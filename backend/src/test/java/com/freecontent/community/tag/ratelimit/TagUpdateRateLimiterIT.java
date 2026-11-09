package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.tag.config.TagCreationProperties;
import com.freecontent.community.tag.config.TagQueryProperties;
import com.freecontent.community.tag.config.TagUpdateProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class TagUpdateRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:tag-update-limit:" + UUID.randomUUID() + ":";
    @AfterEach void cleanup() {
        Set<String> keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void hourlyUserAndMinuteSourceCountersAreAtomicAndIsolatedFromCreationAndQueries() {
        var limiter = new TagUpdateRateLimiter(redis, new TagUpdateProperties(3, 2, prefix));
        limiter.acquire("admin-a", "source");
        limiter.acquire("admin-a", "source");
        var userLimit = assertThrows(BusinessException.class, () -> limiter.acquire("admin-a", "source"));
        assertEquals(ErrorCode.RATE_LIMITED, userLimit.errorCode());
        assertTrue(userLimit.retryAfterSeconds() > 3500 && userLimit.retryAfterSeconds() <= 3600);
        limiter.acquire("admin-b", "source");
        var sourceLimit = assertThrows(BusinessException.class, () -> limiter.acquire("admin-c", "source"));
        assertEquals(ErrorCode.RATE_LIMITED, sourceLimit.errorCode());
        assertTrue(sourceLimit.retryAfterSeconds() >= 1 && sourceLimit.retryAfterSeconds() <= 60);
        long sourceTtl = redis.getExpire(prefix + "{tag-update}:source:" + TokenDigest.sha256("source"), TimeUnit.SECONDS);
        assertTrue(sourceTtl >= 1 && sourceTtl <= 60);
        new TagCreationRateLimiter(redis, new TagCreationProperties(3, 2, prefix)).acquire("admin-a", "source");
        new TagQueryRateLimiter(redis, new TagQueryProperties(3, 2, prefix)).acquire("admin-a", "source");
    }
}
