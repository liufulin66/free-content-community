-- 两个键共用{sessions}槽，当前身份来自认证上下文，不接受客户端指定目标用户或会话。
-- Lua发生运行时错误不会自动回滚，先检查索引类型，避免DEL之后才遇到WRONGTYPE。
local indexType = redis.call('TYPE', KEYS[2]).ok
if indexType ~= 'none' and indexType ~= 'zset' then
    return redis.error_reply('SESSION_INDEX_INVALID')
end
if redis.call('EXISTS', KEYS[1]) == 1 then
    local identity = redis.call('HMGET', KEYS[1], 'userId', 'sessionId')
    if identity[1] ~= ARGV[1] or identity[2] ~= ARGV[2] then return -1 end
end
local deleted = redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], ARGV[3])
return deleted
