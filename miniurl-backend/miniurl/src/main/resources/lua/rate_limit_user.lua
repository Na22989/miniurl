-- rate_limit_user.lua — Redis ZSET 滑动窗口用户级限流
--
-- KEYS[1] = rate:user:{userId}:{action}    Redis key
-- ARGV[1] = window_ms                      窗口大小（毫秒）
-- ARGV[2] = max_requests                   窗口内最大请求数
-- ARGV[3] = now_ms                         当前毫秒时间戳（Java: System.currentTimeMillis()）
-- ARGV[4] = member                         请求唯一标识（Java: String.valueOf(System.nanoTime())）
--
-- 返回: {allowed, remaining, reset_time_ms}
--   allowed:       1=放行, 0=拒绝
--   remaining:     剩余可用次数
--   reset_time_ms: 窗口重置毫秒时间戳（被拒绝时用于计算 Retry-After）

local key          = KEYS[1]
local window_ms    = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local now_ms       = tonumber(ARGV[3])
local member       = ARGV[4]

-- 参数校验
if not window_ms or window_ms <= 0 then
    return redis.error_reply("窗口大小必须大于0")
end
if not max_requests or max_requests <= 0 then
    return redis.error_reply("最大请求数必须大于0")
end
if not now_ms or now_ms <= 0 then
    return redis.error_reply("时间戳无效")
end
if not member or member == "" then
    return redis.error_reply("请求标识不能为空")
end

-- 1. 清理过期记录：删除窗口外的所有成员
local window_start = now_ms - window_ms
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

-- 2. 统计窗口内当前请求数
local current_count = redis.call('ZCARD', key)

-- 3. 判断是否放行
local allowed = 0
if current_count < max_requests then
    allowed = 1
    -- 4. 记录本次请求（score=时间戳, member=唯一标识）
    redis.call('ZADD', key, now_ms, member)
    current_count = current_count + 1
end

-- 5. 剩余可用次数
local remaining = max_requests - current_count
if remaining < 0 then
    remaining = 0
end

-- 6. 计算窗口重置时间（最早请求过期 = 最早 score + window_ms）
local reset_time_ms
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
if oldest and #oldest >= 2 then
    reset_time_ms = tonumber(oldest[2]) + window_ms
else
    -- 窗口为空（被拒绝且无历史记录），等完整窗口
    reset_time_ms = now_ms + window_ms
end

-- 7. 设置 TTL（窗口时长 + 1s 缓冲，防僵尸 key）
local expire_seconds = math.ceil(window_ms / 1000) + 1
redis.call('EXPIRE', key, expire_seconds)

return {allowed, remaining, reset_time_ms}