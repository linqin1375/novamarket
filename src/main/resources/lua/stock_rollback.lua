-- A retained reservation WAL is the authority to roll back, NOT a user's aggregate counter.
local walKey = KEYS[1]
if redis.call('EXISTS', walKey) == 0 then return 0 end
if redis.call('HGET', walKey, 'activityId') ~= ARGV[1] or redis.call('HGET', walKey, 'userId') ~= ARGV[2] then return 0 end
local shard = redis.call('HGET', walKey, 'shardNo')
local stockKey = ARGV[4] .. 'stock:act:' .. ARGV[1] .. ':shard:' .. shard
if redis.call('EXISTS', stockKey) == 0 then return 0 end
redis.call('INCR', stockKey)
local used = tonumber(redis.call('GET', KEYS[2]) or '0')
if used > 0 then redis.call('DECR', KEYS[2]) end
local occupied = tonumber(redis.call('HGET', KEYS[5], shard) or '0')
if occupied > 0 then redis.call('HINCRBY', KEYS[5], shard, -1) end
redis.call('DEL', KEYS[3], walKey, KEYS[6])
redis.call('ZREM', KEYS[4], ARGV[3])
return 1
