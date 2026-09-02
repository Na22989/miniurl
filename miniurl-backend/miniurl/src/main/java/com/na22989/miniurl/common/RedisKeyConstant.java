package com.na22989.miniurl.common;

/**
 * Redis Key 统一常量，避免业务类之间出现反向依赖。
 * <p>
 * 所有 Redis key 前缀集中在此管理。
 */
public final class RedisKeyConstant {

    private RedisKeyConstant() {
    }

    /** JWT 黑名单（主动失效）：value 为拉黑时间戳(ms)，TTL 与 jwt.refresh-expiration 对齐 */
    public static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";

    /** 短链 → 长链映射缓存 */
    public static final String SHORT_CODE_PREFIX = "shortlink:code:";

    /** 短链点击计数缓存（异步累加，定时任务回写 DB） */
    public static final String CLICK_COUNT_PREFIX = "shortlink:click:";

    /** IP 级限流（令牌桶），key 格式 rate:ip:{ip} */
    public static final String RATE_IP_KEY_PREFIX = "rate:ip:";

    /** 用户级限流（滑动窗口），key 格式 rate:user:{userId}:{action} */
    public static final String RATE_USER_KEY_PREFIX = "rate:user:";

    /** 全局限流（令牌桶），key 格式 rate:global:{action}，全局共享不分维度 */
    public static final String RATE_GLOBAL_KEY_PREFIX = "rate:global:";

    /** 短链重建锁（缓存击穿单飞）：value 为持锁者 UUID，TTL 5s */
    public static final String SHORT_CODE_LOCK_PREFIX = "shortlink:rebuild:lock:";
}
