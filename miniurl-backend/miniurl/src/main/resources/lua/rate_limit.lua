-- rate_limit.lua — Redis Lua 令牌桶限流
--
-- KEYS[1] = rate:ip:{ip}        Redis key
-- ARGV[1] = rate                令牌生成速率（个/秒）
-- ARGV[2] = capacity            桶容量上限
-- ARGV[3] = now                 当前毫秒时间戳（Java: System.currentTimeMillis()）
-- ARGV[4] = requested           本次请求消耗令牌数（通常为 1）
--
-- 返回: {allowed, remaining}
--   allowed:   1=放行, 0=拒绝
--   remaining: 扣减后剩余令牌数

local key       = KEYS[1]
local rate      = tonumber(ARGV[1])
local capacity  = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- 参数校验
if not rate or rate <= 0 then
    return redis.error_reply("速率必须大于0")
end
if not capacity or capacity <= 0 then
    return redis.error_reply("容量必须大于0")
end
if not now or now <= 0 then
    return redis.error_reply("当前毫秒时间戳必须大于0")
end
if not requested or requested <= 0 then
    return redis.error_reply("本次请求消耗令牌数必须大于0")
end
if requested > capacity then
    return redis.error_reply("本次请求消耗令牌数不得超过容量")
end

local last_time
local last_tokens

-- 解析已有桶状态（格式: "timestamp,tokens"）
local bucket = redis.call('GET', key)
if bucket then
    local comma_pos = string.find(bucket, ",")
    if comma_pos then
        last_time   = tonumber(string.sub(bucket, 1, comma_pos - 1))
        last_tokens = tonumber(string.sub(bucket, comma_pos + 1))
    else
        -- 数据异常，重置桶
        last_time   = now
        last_tokens = capacity
    end
else
    -- 第一次访问，桶是满的
    last_time   = now
    last_tokens = capacity
end

-- 计算补充令牌数（保留浮点精度，不做 math.floor）
local elapsed_ms = now - last_time
if elapsed_ms < 0 then
    -- 时钟回拨：保守策略，拒绝本次请求，不修改桶状态
    return {0, math.floor(last_tokens)}
end
local refill     = elapsed_ms / 1000 * rate
local new_tokens = math.min(capacity, last_tokens + refill)

-- 判断是否放行
local allowed = 0
if new_tokens >= requested then
    allowed    = 1
    new_tokens = new_tokens - requested
end

-- 动态 TTL：从空桶恢复到满桶的秒数 + 1 秒余量
local expire_seconds = math.ceil(capacity / rate) + 1
-- 最大 TTL：1 天
if expire_seconds > 86400 then
    expire_seconds = 86400
end

-- 写回 Redis
redis.call('SET', key, now .. ',' .. new_tokens, 'EX', expire_seconds)


return {allowed, math.floor(new_tokens)}
