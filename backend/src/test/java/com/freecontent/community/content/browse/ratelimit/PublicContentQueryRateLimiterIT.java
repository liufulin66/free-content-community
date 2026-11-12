package com.freecontent.community.content.browse.ratelimit;
import com.freecontent.community.content.browse.config.PublicContentQueryProperties;
import com.freecontent.community.content.config.ContentQueryProperties;
import com.freecontent.community.content.ratelimit.ContentQueryRateLimiter;
import com.freecontent.community.common.error.*;
import com.freecontent.community.auth.security.TokenDigest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"app.auth.email-challenge.delivery-enabled=false","app.media.upload-cleanup.enabled=false"})
class PublicContentQueryRateLimiterIT {
    @Autowired private StringRedisTemplate redis;
    @Test void anonymousAndAuthenticatedRequestsShareOnlyThePublicSourceBudget(){
        String prefix="test:public-budget:"+UUID.randomUUID()+":";
        try{
            var limiter=new PublicContentQueryRateLimiter(redis,new PublicContentQueryProperties(3,2,prefix));
            limiter.acquire(null,"source");limiter.acquire("user","source");limiter.acquire("user","source");
            var error=assertThrows(BusinessException.class,()->limiter.acquire(null,"source"));assertEquals(ErrorCode.RATE_LIMITED,error.errorCode());assertTrue(error.retryAfterSeconds()>=1);
            assertThrows(BusinessException.class,()->limiter.acquire("user","other-source"));
            new ContentQueryRateLimiter(redis,new ContentQueryProperties(3,2,prefix)).acquire("user","source");
            assertEquals("3",redis.opsForValue().get(prefix+"{content-public-query}:source:"+TokenDigest.sha256("source")));
        }finally{var keys=redis.keys(prefix+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    }
    @Test void concurrentVisitorsCannotOverspendTheSourceWindow()throws Exception{
        String prefix="test:public-concurrent:"+UUID.randomUUID()+":";var pool=Executors.newFixedThreadPool(8);
        try{
            var limiter=new PublicContentQueryRateLimiter(redis,new PublicContentQueryProperties(5,3,prefix));var start=new CountDownLatch(1);List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<16;i++)tasks.add(pool.submit(()->{start.await();try{limiter.acquire(null,"source");return true;}catch(BusinessException e){assertEquals(ErrorCode.RATE_LIMITED,e.errorCode());return false;}}));
            start.countDown();int accepted=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))accepted++;assertEquals(5,accepted);
        }finally{pool.shutdownNow();var keys=redis.keys(prefix+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    }
}
