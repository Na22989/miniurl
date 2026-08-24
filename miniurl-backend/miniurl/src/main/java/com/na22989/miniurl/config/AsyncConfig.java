package com.na22989.miniurl.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;


/**
 * 异步线程池：承载访问事件消费（点击计数 Redis INCR + 访问日志落库）。
 * <p>核心 4 / 最大 8 / 有界队列 1000：任务均为单条轻量操作，池只需把重定向线程
 * 快速卸下的请求短暂缓冲，无需大池；有界队列吸收瞬时突发，避免无限堆积内存。
 * 不用 MQ：计数场景对可靠性要求不高，线程池足够，省去运维复杂度。</p>
 * <p>队列打满时 CallerRunsPolicy 回压到调用线程而非丢弃——计数/日志可迟到不可丢；
 * 代价是极端流量下重定向 RT 上升，属有意取舍。</p>
 */
@Configuration
@RequiredArgsConstructor
public class AsyncConfig {

    private final MdcTaskDecorator mdcTaskDecorator;

    @Bean
    public Executor clickCountExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("click-count-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 把请求线程的 MDC 搬运到异步线程：点击计数日志与触发它的请求共享同一 traceId
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.initialize();
        return executor;
    }
}
