local requestKey = KEYS[1]
local stockKey = KEYS[2]
local stream = KEYS[3]
local group = ARGV[1]
local messageId = ARGV[2]
local userId = ARGV[3]
local orderId = ARGV[4]
local duplicate = ARGV[5]

if redis.call('HGET', requestKey, 'status') ~= 'CONFIRMED' then
    if duplicate == '1' then
        if redis.call('EXISTS', stockKey) == 0 then
            return redis.error_reply('stock key missing; manual reconciliation required')
        end
        redis.call('INCR', stockKey)
    end
    redis.call('HSET', requestKey, 'userId', userId, 'status', 'CONFIRMED', 'orderId', orderId)
    redis.call('EXPIRE', requestKey, 604800)
end
return redis.call('XACK', stream, group, messageId)
