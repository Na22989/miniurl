package com.na22989.miniurl.config;

import com.na22989.miniurl.filter.TraceIdFilter;
import com.na22989.miniurl.util.TraceIdUtil;
import jakarta.servlet.DispatcherType;
import org.slf4j.MDC;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import static com.na22989.miniurl.common.TraceConstant.TRACE_ID;

/**
 * TraceId 相关 Bean 注册：过滤器注册 + 异步/调度 MDC 装饰器。
 * <p>
 * TraceIdFilter 故意不加 @Component：否则 Boot 自动注册 + 此处显式注册 = 注册两次，
 * MDC 被 put/clear 两轮、响应头写两次。
 */
@Configuration
public class TraceConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>(new TraceIdFilter());
        bean.addUrlPatterns("/*");
        // ERROR 必须显式声明：自动注册只挂 REQUEST，拦截器抛的异常经容器转发到 /error 会漏掉，
        // 而异常路径恰恰最需要 traceId
        bean.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        // +10 而非 HIGHEST_PRECEDENCE 本身：留出并列余地，避免与将来抢最高优先级的 Filter
        // 出现 order 相同、排序未定义；仍早于 Boot 内置过滤器 HiddenHttpMethod(-10000) 等
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }

    @Bean
    public MdcTaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    /**
     * 只装饰 Boot 自动配置的 taskScheduler，不替换它——自定义 taskScheduler Bean 会整体接管
     * TaskSchedulingAutoConfiguration，一旦漏写 poolSize=1，两个 @Scheduled 将从串行变并发。
     */
    @Bean
    public ThreadPoolTaskSchedulerCustomizer tracePoolTaskSchedulerCustomizer() {
        return taskScheduler -> taskScheduler.setTaskDecorator(runnable -> () -> {
            // 定时任务无上游，每轮自建
            MDC.put(TRACE_ID, TraceIdUtil.generate());
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        });
    }
}
