package com.na22989.miniurl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;


@Configuration
public class AsyncConfig {

    @Bean
    public Executor clickCountExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数：正常情况下的线程数量
        executor.setCorePoolSize(4);

        // 最大线程数：当核心线程数不够用时，会创建新的线程，但总线程数不能超过最大线程数
        executor.setMaxPoolSize(8);

        // 队列容量：当线程数达到最大线程数时，新任务会被放入队列中等待执行
        executor.setQueueCapacity(1000);

        // 线程名前缀：方便日志排查
        executor.setThreadNamePrefix("click-count-");

        // 线程空闲时间：当线程空闲时间超过这个时间时，线程会被销毁
        executor.setKeepAliveSeconds(60);

        // 拒绝策略：队列满了怎么办？
        // CallerRunsPolicy = 由调用者线程（主线程）执行，不丢失任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 初始化
        executor.initialize();

        return executor;
    }
}
