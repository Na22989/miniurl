package com.na22989.miniurl.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetricsRecorder {

    private final MeterRegistry registry;

    public void recordRedirect(String source) {    // l1 / l2 / db
        registry.counter("miniurl.redirect.total", "source", source).increment();
    }

    public void recordLinkCreated() {
        registry.counter("miniurl.link.created").increment();
    }

    public void recordRedirectFail(String reason) { // not_found / expired
        registry.counter("miniurl.redirect.fail", "reason", reason).increment();
    }

    public void recordExpiredReject(String source) {  // l1 / l2（缓存命中但值已过业务期限）
        registry.counter("miniurl.redirect.expired_reject", "source", source).increment();
    }

    /**
     * Redis 降级计数：按操作类型分类，供运维观测 Redis 不可用时的降级频率与分布。
     *
     * @param operation l2_get / l2_get_spin / l2_set / l2_delete / lock_acquire / lock_release / delete / blacklist_get
     */
    public void recordRedisDegraded(String operation) {
        registry.counter("miniurl.redis.degraded", "op", operation).increment();
    }

    /**
     * 读取计数器当前累计值，供定时任务按窗口增量计算（logCacheStats 用）。
     *
     * @param name 指标名（如 miniurl.redirect.total）
     * @param tags 标签键值对（如 "source", "l1"）
     * @return 累计计数（整数增量，取整安全）
     */
    public long count(String name, String... tags) {
        return (long) registry.counter(name, tags).count();
    }
}
