package com.freecontent.community.media.ratelimit;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.media.config.MediaTaskQueryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaTaskQueryRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MediaTaskQueryRateLimiter limiter = new MediaTaskQueryRateLimiter(redis,
            new MediaTaskQueryProperties(120, 60, "test:task-query:"));

    @Test
    void redisConnectionFailureReturns503WithoutExposingTheDependencyMessage() {
        doThrow(new RedisConnectionFailureException("internal connection details"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("owner", "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
        assertFalse(failure.getMessage().contains("internal connection details"));
        verify(redis).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void anUnconfirmedRedisResultDoesNotAllowTheRequest() {
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("owner", "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
    }
}
