-- Standalone Redis only: every stock shard and the quota/reservation are checked in one Lua call.
-- KEYS: limit, idempotency, WAL hash, WAL index, shard usage, then ALL stock shards.
-- ARGV: requestId, limit, preferredShard (0-based), activityId, userId, idemTTL.
local requestId, limit, first = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])
local activityId, userId = ARGV[4], ARGV[5]
local existing = redis.call('GET', KEYS[2])
local pending = redis.call('EXISTS', KEYS[3]) == 1
if existing or pending then
  if redis.call('HGET', KEYS[2] .. ':owner', 'activityId') ~= activityId or redis.call('HGET', KEYS[2] .. ':owner', 'userId') ~= userId then
    return {5, '', '-1'}
  end
  local shard = redis.call('HGET', KEYS[2] .. ':owner', 'shardNo')
  return {3, existing or redis.call('HGET', KEYS[3], 'claimNo'), shard}
end
local count = #KEYS - 5
local chosen = -1
-- Validate that all shards exist before any mutation.
for offset = 0, count - 1 do
  local n = (first + offset) % count
  local value = redis.call('GET', KEYS[6 + n])
  if not value then return {4, '', '-1'} end
  if chosen < 0 and tonumber(value) > 0 then chosen = n end
end
if tonumber(redis.call('GET', KEYS[1]) or '0') >= limit then return {2, '', '-1'} end
if chosen < 0 then return {1, '', '-1'} end
redis.call('DECR', KEYS[6 + chosen])
redis.call('INCR', KEYS[1])
redis.call('HINCRBY', KEYS[5], tostring(chosen), 1)
local claimNo = 'CLM' .. requestId
local time = redis.call('TIME')
local nowMs = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('HSET', KEYS[3], 'activityId', activityId, 'userId', userId, 'shardNo', tostring(chosen), 'claimNo', claimNo, 'at', tostring(nowMs))
-- Pending WAL deliberately has NO TTL: unresolved state must never silently disappear.
redis.call('ZADD', KEYS[4], nowMs, requestId)
redis.call('SET', KEYS[2], claimNo, 'EX', tonumber(ARGV[6]))
redis.call('HSET', KEYS[2] .. ':owner', 'activityId', activityId, 'userId', userId, 'shardNo', tostring(chosen))
-- Owner metadata lives with pending WAL; clearWal gives it a bounded post-commit TTL.
return {0, claimNo, tostring(chosen)}
