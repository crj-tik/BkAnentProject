-- KEYS: head, snapshot:<revision>, durable per-user fence (all the same hash slot).
-- ARGV: revision, epoch, hash, status, checkedAt, notAfter, head JSON,
--       snapshot envelope JSON, snapshot TTL seconds, PUBLISH|RENEW.
-- Never use tonumber for BIGINT revisions/epochs: Lua doubles lose integer precision.
local function decimal(value)
    return value and string.match(value, '^%d+$') and (value == '0' or string.sub(value, 1, 1) ~= '0')
end
local function compare(left, right)
    if string.len(left) ~= string.len(right) then
        if string.len(left) < string.len(right) then return -1 else return 1 end
    end
    if left == right then return 0 end
    if left < right then return -1 else return 1 end
end
if not decimal(ARGV[1]) or not decimal(ARGV[2]) or not decimal(ARGV[5]) or not decimal(ARGV[6])
        or ARGV[1] == '0' or (ARGV[10] ~= 'PUBLISH' and ARGV[10] ~= 'RENEW') then return -6 end
local now = tonumber(redis.call('TIME')[1])
local checked = tonumber(ARGV[5])
local expires = tonumber(ARGV[6])
if checked > now or expires <= now or expires <= checked or expires > checked + 30 then return -4 end
local old = redis.call('HMGET', KEYS[3], 'revision', 'epoch', 'hash', 'status', 'checkedAt')
local same = false
if old[1] then
    if not decimal(old[1]) or not decimal(old[2]) or not decimal(old[5]) then return -6 end
    local revisionOrder = compare(ARGV[1], old[1])
    if revisionOrder < 0 or compare(ARGV[2], old[2]) < 0 then return -1 end
    same = revisionOrder == 0
    if same and (ARGV[2] ~= old[2] or ARGV[3] ~= old[3] or ARGV[4] ~= old[4]) then return -2 end
    if compare(ARGV[5], old[5]) < 0 then return -3 end
end
local existing = redis.call('GET', KEYS[2])
if ARGV[10] == 'RENEW' and (not same or not existing) then return -5 end
if existing then
    local valid, envelope = pcall(cjson.decode, existing)
    if not valid or envelope.contentHash ~= ARGV[3] then return -2 end
end
-- Duplicate checks in the same second are idempotent; no new nonce or sliding storage TTL.
if same and ARGV[5] == old[5] and existing and redis.call('EXISTS', KEYS[1]) == 1 then return 2 end
if not existing then
    redis.call('SET', KEYS[2], ARGV[8], 'EX', ARGV[9])
end
-- Snapshot exists before head is switched, and expiry is absolute, never publication-time + 30.
redis.call('SET', KEYS[1], ARGV[7])
redis.call('EXPIREAT', KEYS[1], ARGV[6])
redis.call('HSET', KEYS[3], 'revision', ARGV[1], 'epoch', ARGV[2], 'hash', ARGV[3],
    'status', ARGV[4], 'checkedAt', ARGV[5])
return 1
