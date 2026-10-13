local code = redis.call('GET', KEYS[1])
if not code then return -1 end

local attempts = tonumber(redis.call('GET', KEYS[2]) or '0')
local maxAttempts = tonumber(ARGV[2])
if attempts >= maxAttempts then return -2 end

if code == ARGV[1] then
    redis.call('DEL', KEYS[1], KEYS[2])
    return 1
end

attempts = redis.call('INCR', KEYS[2])
if attempts == 1 then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3])) end
if attempts >= maxAttempts then return -2 end
return 0
