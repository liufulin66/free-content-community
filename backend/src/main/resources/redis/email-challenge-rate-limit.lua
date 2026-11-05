-- KEYS: 同邮箱同用途冷却、邮箱小时次数、来源小时次数。
-- 小时窗口从首次申请开始，拒绝时不增加其他计数。
local cooldown = redis.call('PTTL', KEYS[1])
local emailCount = tonumber(redis.call('GET', KEYS[2]) or '0')
local sourceCount = tonumber(redis.call('GET', KEYS[3]) or '0')
local retry = math.max(0, cooldown)
if emailCount >= tonumber(ARGV[2]) then
    retry = math.max(retry, redis.call('PTTL', KEYS[2]))
end
if sourceCount >= tonumber(ARGV[3]) then
    retry = math.max(retry, redis.call('PTTL', KEYS[3]))
end
if retry > 0 or emailCount >= tonumber(ARGV[2]) or sourceCount >= tonumber(ARGV[3]) then
    return {0, math.max(1, retry), 0}
end
local emailCurrent = redis.call('INCR', KEYS[2])
if emailCurrent == 1 then redis.call('PEXPIRE', KEYS[2], 3600000) end
local sourceCurrent = redis.call('INCR', KEYS[3])
if sourceCurrent == 1 then redis.call('PEXPIRE', KEYS[3], 3600000) end
redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
local now = redis.call('TIME')
return {1, tonumber(now[1]), tonumber(now[2])}
