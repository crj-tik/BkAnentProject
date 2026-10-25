-- Auth-only shared active actor index. No run/context/token contents and no KEYS scan.
-- ARGV: REGISTER|PAGE, actor|offset, absolute expiry|batch, capacity.
local now = tonumber(redis.call('TIME')[1])
local capacity = tonumber(ARGV[4])
if not capacity or capacity < 1 or capacity > 10000 then return redis.error_reply('invalid capacity') end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
if ARGV[1] == 'REGISTER' then
    local expiry = tonumber(ARGV[3])
    if not string.match(ARGV[2], '^[1-9]%d*$') or string.len(ARGV[2]) > 19
            or not expiry or expiry <= now or expiry > now + 300 then return 0 end
    local old = redis.call('ZSCORE', KEYS[1], ARGV[2])
    if not old and redis.call('ZCARD', KEYS[1]) >= capacity then return 0 end
    if not old or expiry > tonumber(old) then redis.call('ZADD', KEYS[1], expiry, ARGV[2]) end
    redis.call('EXPIRE', KEYS[1], 301)
    return 1
elseif ARGV[1] == 'PAGE' then
    local offset = tonumber(ARGV[2])
    local batch = tonumber(ARGV[3])
    if not offset or offset < 0 or offset >= capacity or not batch or batch < 1 or batch > 100 then
        return redis.error_reply('invalid page')
    end
    return redis.call('ZRANGE', KEYS[1], offset, offset + batch - 1, 'WITHSCORES')
end
return redis.error_reply('invalid operation')
