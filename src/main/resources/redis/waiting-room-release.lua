-- 대기열 serving 포인터를 batch 만큼 전진시키되, 발급된 티켓 수(seq)를 넘지 않게 클램프한다.
-- KEYS[1]=seq, KEYS[2]=serving, ARGV[1]=batch  → 반환: 갱신된 serving
local seq = tonumber(redis.call('GET', KEYS[1]) or '0')
local serving = tonumber(redis.call('GET', KEYS[2]) or '0')
local next = serving + tonumber(ARGV[1])
if next > seq then
    next = seq
end
redis.call('SET', KEYS[2], next)
return next
