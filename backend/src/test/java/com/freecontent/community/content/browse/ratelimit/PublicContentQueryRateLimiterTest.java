package com.freecontent.community.content.browse.ratelimit;
import com.freecontent.community.content.browse.config.PublicContentQueryProperties;
import com.freecontent.community.common.error.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PublicContentQueryRateLimiterTest {
    @ParameterizedTest @MethodSource("invalidReplies")
    void uncertainQuotaDecisionsFailClosed(String viewer,List<?> reply){
        var redis=mock(StringRedisTemplate.class);doReturn(reply).when(redis).execute(any(RedisScript.class),anyList(),any());
        doReturn(reply).when(redis).execute(any(RedisScript.class),anyList(),any(),any());
        var limiter=new PublicContentQueryRateLimiter(redis,new PublicContentQueryProperties(120,60,"test:"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,()->limiter.acquire(viewer,"source")).errorCode());
    }
    static Stream<Arguments> invalidReplies(){return Stream.<List<?>>of(null,List.of(),List.of(1L),List.of(2L,0L),List.of("bad",0L),List.of(1L,"bad"))
        .flatMap(reply->Stream.of(Arguments.of(null,reply),Arguments.of("user",reply)));}
}
