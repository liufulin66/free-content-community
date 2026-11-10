package com.freecontent.community.content.ratelimit;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.config.ContentMediaPreviewProperties;
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

class ContentMediaPreviewRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ContentMediaPreviewRateLimiter limiter = new ContentMediaPreviewRateLimiter(redis,new ContentMediaPreviewProperties(300,120,60,"test:preview:"));
    @Test void connectionFailuresStopSignatureIssuingWithoutLeakingDetails() {
        doThrow(new RedisConnectionFailureException("sensitive detail")).when(redis).execute(any(RedisScript.class),anyList(),any(),any());
        var failure = assertThrows(BusinessException.class,() -> limiter.acquire("user","source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,failure.errorCode()); assertFalse(failure.getMessage().contains("sensitive detail"));
    }
    @ParameterizedTest @MethodSource("invalidResults")
    void uncertainDecisionsFailClosed(List<?> result) {
        doReturn(result).when(redis).execute(any(RedisScript.class),anyList(),any(),any());
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,() -> limiter.acquire("user","source")).errorCode());
    }
    static Stream<List<?>> invalidResults() { return Stream.of(null,List.of(),List.of(1L),List.of(2L,0L),List.of("ok",0L),List.of(1L,"wait")); }
}
