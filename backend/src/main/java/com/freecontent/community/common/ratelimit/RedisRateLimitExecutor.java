package com.freecontent.community.common.ratelimit;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 仅执行原子配额脚本；业务键、配额、提示及调用时机由所属模块决定。 */
public final class RedisRateLimitExecutor {
    public enum Policy {
        USER_MINUTE_SOURCE_MINUTE("redis/rate-limit/user-minute-source-minute.lua"),
        USER_HOUR_SOURCE_MINUTE("redis/rate-limit/user-hour-source-minute.lua"),
        SOURCE_MINUTE("redis/tag-active-query-rate-limit.lua");

        private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

        Policy(String resource) {
            script.setLocation(new ClassPathResource(resource));
            script.setResultType(List.class);
        }
    }

    private final StringRedisTemplate redis;

    public RedisRateLimitExecutor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void acquire(Policy policy, List<String> keys, String limitedMessage,
                        String unavailableMessage, int... quotas) {
        try {
            Object[] arguments = Arrays.stream(quotas).mapToObj(Integer::toString).toArray();
            List<?> result = redis.execute(policy.script, keys, arguments);
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Number decision)
                    || !(result.get(1) instanceof Number waitMillis)
                    || (decision.longValue() != 0 && decision.longValue() != 1)) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailableMessage);
            }
            if (decision.longValue() == 0) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, limitedMessage, null,
                        Math.max(1, (waitMillis.longValue() + 999) / 1000), List.of());
            }
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailableMessage);
        }
    }
}
