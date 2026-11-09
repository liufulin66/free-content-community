-- 公开标签只有来源分钟配额；检查、计数和到期设置必须原子执行。
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count >= tonumber(ARGV[1]) then
    return {0, math.max(1, redis.call('PTTL', KEYS[1]))}
end
if redis.call('INCR', KEYS[1]) == 1 then redis.call('EXPIRE', KEYS[1], 60) end
return {1, 0}
