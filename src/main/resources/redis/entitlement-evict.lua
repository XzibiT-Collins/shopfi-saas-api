-- KEYS = cache key, generation key, cache key, generation key, ...; ARGV[1] = generation counter TTL millis
-- The counter must outlive any in-flight reader, so it is long-lived and refreshed on every eviction.
for i = 1, #KEYS, 2 do
  redis.call('INCR', KEYS[i + 1])
  redis.call('PEXPIRE', KEYS[i + 1], ARGV[1])
  redis.call('DEL', KEYS[i])
end
return #KEYS / 2
