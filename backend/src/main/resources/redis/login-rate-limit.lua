-- 两个配额一起检查，所有获准进入登录业务的尝试均计数，不区分账号是否存在。
local emailCount = tonumber(redis.call('GET', KEYS[1]) or '0')
local sourceCount = tonumber(redis.call('GET', KEYS[2]) or '0')
local retry = 0
if emailCount >= tonumber(ARGV[1]) then retry = math.max(retry, redis.call('PTTL', KEYS[1])) end
if sourceCount >= tonumber(ARGV[2]) then retry = math.max(retry, redis.call('PTTL', KEYS[2])) end
if emailCount >= tonumber(ARGV[1]) or sourceCount >= tonumber(ARGV[2]) then
    return {0, math.max(1, retry)}
end
if redis.call('INCR', KEYS[1]) == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3])) end
if redis.call('INCR', KEYS[2]) == 1 then redis.call('EXPIRE', KEYS[2], 60) end
return {1, 0}
