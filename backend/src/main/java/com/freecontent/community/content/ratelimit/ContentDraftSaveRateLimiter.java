package com.freecontent.community.content.ratelimit;

import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.config.ContentDraftSaveProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ContentDraftSaveRateLimiter {
    private final StringRedisTemplate redis;
    private final ContentDraftSaveProperties properties;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();
    public ContentDraftSaveRateLimiter(StringRedisTemplate redis,ContentDraftSaveProperties properties) {
        this.redis = redis; this.properties = properties;
        script.setLocation(new ClassPathResource("redis/upload-query-rate-limit.lua")); script.setResultType(List.class);
    }
    public void acquire(String actor,String sourceIp) {
        String prefix = properties.redisKeyPrefix()+"{content-draft-save}:";
        try {
            List<?> result = redis.execute(script,List.of(prefix+"user:"+TokenDigest.sha256(actor),prefix+"source:"+TokenDigest.sha256(sourceIp)),
                    Integer.toString(properties.userPerMinuteLimit()),Integer.toString(properties.ipPerMinuteLimit()));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Number decision)
                    || !(result.get(1) instanceof Number waitMillis) || (decision.longValue() != 0 && decision.longValue() != 1)) throw unavailable();
            if (decision.longValue() == 0) throw new BusinessException(ErrorCode.RATE_LIMITED,"草稿保存过于频繁",null,
                    Math.max(1,(waitMillis.longValue()+999)/1000),List.of());
        } catch (DataAccessException ex) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"草稿保存限流服务暂不可用"); }
}
