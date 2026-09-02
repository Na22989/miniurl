-- 原子化锁释放：仅当值匹配时删除，防止误删他人的锁
-- KEYS[1]: 锁 key
-- ARGV[1]: 预期的锁值（owner token）
-- 返回：1=删除成功，0=值不匹配（锁已被他人持有或已过期）

local current = redis.call('GET', KEYS[1])
if current == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
else
    return 0
end