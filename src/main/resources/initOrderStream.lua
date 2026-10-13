local result = redis.pcall('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
if type(result) == 'table' and result.err then
    if string.find(result.err, 'BUSYGROUP') then return 0 end
    return redis.error_reply(result.err)
end
return 1
