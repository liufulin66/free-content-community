-- 仅删除旧认证版本，保留密码重置提交后新登录产生的会话。
-- 正十进制字符串按长度及字典序比较，避免BIGINT转Lua浮点数丢失精度。
local function older(version, cutoff)
    if #version ~= #cutoff then return #version < #cutoff end
    return version < cutoff
end
local members = redis.call('ZRANGE', KEYS[1], 0, -1)
local stale = {}
-- 先读取并检查所有项，避免遇到损坏记录后只清理了一部分。
for _, digest in ipairs(members) do
    local key = ARGV[1] .. digest
    if redis.call('EXISTS', key) == 0 then
        table.insert(stale, digest)
    else
        local identity = redis.call('HMGET', key, 'userId', 'authVersion')
        if identity[1] ~= ARGV[2] then return redis.error_reply('SESSION_INDEX_OWNER_INVALID') end
        if not identity[2] or not string.match(identity[2], '^[1-9][0-9]*$') then
            return redis.error_reply('SESSION_VERSION_INVALID')
        end
        if older(identity[2], ARGV[3]) then table.insert(stale, digest) end
    end
end
for _, digest in ipairs(stale) do
    redis.call('DEL', ARGV[1] .. digest)
    redis.call('ZREM', KEYS[1], digest)
end
return #stale
