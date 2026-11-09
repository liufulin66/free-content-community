package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.media.config.UploadQueryProperties;
import com.freecontent.community.media.ratelimit.UploadQueryRateLimiter;
import com.freecontent.community.tag.config.TagCreationProperties;
import com.freecontent.community.tag.config.TagQueryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class TagQueryRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    private final String prefix = "test:tag-query-limit:" + UUID.randomUUID() + ":";

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void userAndSourceCountersAreAtomicAndIndependentOfTagCreationAndUploadQuery() {
        var query = new TagQueryRateLimiter(redis, new TagQueryProperties(3, 2, prefix));
        query.acquire("admin-a", "source");
        query.acquire("admin-a", "source");
        var limited = assertThrows(BusinessException.class, () -> query.acquire("admin-a", "source"));
        assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode());
        assertTrue(limited.retryAfterSeconds() >= 1 && limited.retryAfterSeconds() <= 60);
        query.acquire("admin-b", "source");
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(BusinessException.class, () -> query.acquire("admin-c", "source")).errorCode());
        new TagCreationRateLimiter(redis, new TagCreationProperties(3, 2, prefix)).acquire("admin-a", "source");
        new UploadQueryRateLimiter(redis, new UploadQueryProperties(3, 2, prefix)).acquire("admin-a", "source");
    }
}
