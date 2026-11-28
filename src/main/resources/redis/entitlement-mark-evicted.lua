-- KEYS = cache key, generation key, cache key, generation key, ...; ARGV[1] = generation TTL millis,
-- ARGV[2] = marker, ARGV[3] = marker TTL millis. Bumps the generation and replaces the entry by the short-lived marker.
for i = 1, #KEYS, 2 do
  redis.call('INCR', KEYS[i + 1])
  redis.call('PEXPIRE', KEYS[i + 1], ARGV[1])
  redis.call('SET', KEYS[i], ARGV[2], 'PX', ARGV[3])
end
return #KEYS / 2
