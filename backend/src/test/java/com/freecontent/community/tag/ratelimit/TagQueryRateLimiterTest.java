package com.freecontent.community.tag.ratelimit;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.tag.config.TagQueryProperties;
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

class TagQueryRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TagQueryRateLimiter limiter = new TagQueryRateLimiter(redis, new TagQueryProperties(120, 60, "test:tag-query:"));

    @Test
    void redisConnectionFailureIs503WithoutLeakingConnectionDetails() {
        doThrow(new RedisConnectionFailureException("sensitive connection details"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(), any());

        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("admin", "source"));

        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
        assertFalse(failure.getMessage().contains("sensitive connection details"));
    }

    @ParameterizedTest
    @MethodSource("unconfirmedResults")
    void unconfirmedRedisResultsDoNotAllowQueries(List<?> result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), any(), any());
        var failure = assertThrows(BusinessException.class, () -> limiter.acquire("admin", "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
    }

    static Stream<List<?>> unconfirmedResults() {
        return Stream.of(null, List.of(), List.of(2L, 0L), List.of("ok", 0L));
    }
}
