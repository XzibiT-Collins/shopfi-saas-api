-- KEYS[1] = cache key, KEYS[2] = its generation key; ARGV[1] = expected generation, ARGV[2] = JSON, ARGV[3] = TTL millis
local current = redis.call('GET', KEYS[2])
if current == false then current = '0' end
if current == ARGV[1] then
  redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
  return 1
end
return 0
