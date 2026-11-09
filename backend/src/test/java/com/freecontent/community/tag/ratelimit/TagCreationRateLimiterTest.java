package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.common.error.*;
import com.freecontent.community.tag.config.TagCreationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TagCreationRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TagCreationRateLimiter limiter = new TagCreationRateLimiter(redis, new TagCreationProperties(60, 100, "test:tag:"));
    @Test void unavailableRedisReturns503WithoutExposingDetails() {
        doThrow(new RedisConnectionFailureException("internal connection details"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("admin", "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
        assertFalse(failure.getMessage().contains("internal connection details"));
        verify(redis).execute(any(RedisScript.class), anyList(), any(), any());
    }
    @Test void unconfirmedQuotaResultsDoNotAllowCreation() {
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> limiter.acquire("admin", "source")).errorCode());
    }
}
