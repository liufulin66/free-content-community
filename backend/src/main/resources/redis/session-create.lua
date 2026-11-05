-- 同一hash tag保证会话键与用户索引在同一Redis槽内；原子完成清理、创建和淘汰。
if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
local members = redis.call('ZRANGE', KEYS[1], 0, -1)
-- 旧密码验证中的请求不得清理新认证版本的会话。
for _, digest in ipairs(members) do
    local version = redis.call('HGET', ARGV[1] .. digest, 'authVersion')
    if version and tonumber(version) > tonumber(ARGV[5]) then return -1 end
end
for _, digest in ipairs(members) do
    local key = ARGV[1] .. digest
    local version = redis.call('HGET', key, 'authVersion')
    local expires = tonumber(redis.call('HGET', key, 'expiresAt') or '0')
    if not version or version ~= ARGV[5] or expires <= tonumber(ARGV[6]) then
        redis.call('DEL', key)
        redis.call('ZREM', KEYS[1], digest)
    end
end
redis.call('HSET', KEYS[2], 'sessionId', ARGV[3], 'userId', ARGV[4], 'authVersion', ARGV[5],
    'createdAt', ARGV[6], 'expiresAt', ARGV[7], 'lastSeenAt', ARGV[6], 'deviceLabel', ARGV[8])
redis.call('PEXPIREAT', KEYS[2], ARGV[7])
redis.call('ZADD', KEYS[1], ARGV[6], ARGV[2])
-- 索引的过期时间不能早于其中尚有效的会话。
local ttl = redis.call('PTTL', KEYS[1])
local desired = tonumber(ARGV[7]) - tonumber(ARGV[6])
if ttl < desired then redis.call('PEXPIRE', KEYS[1], desired) end
while redis.call('ZCARD', KEYS[1]) > tonumber(ARGV[9]) do
    local oldest = redis.call('ZPOPMIN', KEYS[1], 1)
    redis.call('DEL', ARGV[1] .. oldest[1])
end
return 1
