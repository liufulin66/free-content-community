package com.freecontent.community.media.ratelimit;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.MediaAssetQueryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaAssetQueryRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final MediaAssetQueryRateLimiter limiter = new MediaAssetQueryRateLimiter(redis,
            new MediaAssetQueryProperties(120, 60, "test:asset-query:"));

    @Test void redisConnectionFailureReturns503WithoutLeakingDetails() {
        doThrow(new RedisConnectionFailureException("internal connection details"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("owner", "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
        assertFalse(failure.getMessage().contains("internal connection details"));
        verify(redis).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test void anUnconfirmedRedisResultDoesNotAllowTheRequest() {
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> limiter.acquire("owner", "source")).errorCode());
    }
}
