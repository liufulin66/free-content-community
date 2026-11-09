package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.tag.config.ActiveTagQueryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveTagQueryRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ActiveTagQueryRateLimiter limiter = new ActiveTagQueryRateLimiter(redis, new ActiveTagQueryProperties(120, "test:active-tags:"));

    @Test
    void redisConnectionFailureIs503WithoutInternalDetails() {
        doThrow(new RedisConnectionFailureException("sensitive redis details")).when(redis)
                .execute(any(RedisScript.class), anyList(), any());
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
        assertFalse(failure.getMessage().contains("sensitive redis details"));
    }

    @ParameterizedTest @MethodSource("unconfirmedResults")
    void unconfirmedResultsCannotAllowTheRead(List<?> result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), any());
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> limiter.acquire("source")).errorCode());
    }

    static Stream<List<?>> unconfirmedResults() { return Stream.of(null, List.of(), List.of(2L, 0L), List.of("ok", 0L)); }
}
