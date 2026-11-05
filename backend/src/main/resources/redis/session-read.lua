if redis.call('EXISTS', KEYS[1]) == 0 then return {} end
local values = redis.call('HMGET', KEYS[1], 'sessionId', 'userId', 'authVersion', 'createdAt', 'expiresAt', 'deviceLabel')
for i = 1, 6 do
    if not values[i] then return redis.error_reply('SESSION_DATA_INVALID') end
end
-- 索引若被淘汰，孤立的会话也不能继续认证，否则新登录可能突破数量限制。
if not redis.call('ZSCORE', ARGV[2] .. values[2], ARGV[3]) then
    redis.call('DEL', KEYS[1])
    return {}
end
if tonumber(values[5]) <= tonumber(ARGV[1]) then
    redis.call('DEL', KEYS[1])
    return {}
end
-- 仅更新活动时间，不延长TTL；存在性检查与更新原子执行，避免重新创建已撤销键。
redis.call('HSET', KEYS[1], 'lastSeenAt', ARGV[1])
return values
