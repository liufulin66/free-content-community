local emailCount = tonumber(redis.call('GET', KEYS[1]) or '0')
local sourceCount = tonumber(redis.call('GET', KEYS[2]) or '0')
local retry = 0
if emailCount >= tonumber(ARGV[1]) then
    retry = math.max(retry, redis.call('PTTL', KEYS[1]))
end
if sourceCount >= tonumber(ARGV[2]) then
    retry = math.max(retry, redis.call('PTTL', KEYS[2]))
end
if emailCount >= tonumber(ARGV[1]) or sourceCount >= tonumber(ARGV[2]) then
    return {0, math.max(1, retry)}
end
if redis.call('INCR', KEYS[1]) == 1 then redis.call('PEXPIRE', KEYS[1], 3600000) end
if redis.call('INCR', KEYS[2]) == 1 then redis.call('PEXPIRE', KEYS[2], 60000) end
return {1, 0}
