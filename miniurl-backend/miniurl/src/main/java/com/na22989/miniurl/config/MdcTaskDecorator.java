package com.na22989.miniurl.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步线程池的 MDC 搬运器：把提交线程（请求线程）的 MDC 快照复制到执行线程，
 * 使 @Async 消费（点击计数落库 / Redis INCR）的日志与触发它的请求共享同一 traceId。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (captured != null) {
                    MDC.setContextMap(captured);
                }
                runnable.run();
            } finally {
                // 关键：不能无脑 clear。CallerRunsPolicy 拒绝策略下，回压时执行线程 == 提交线程
                // （请求线程），clear 会把请求自己的 traceId 抹掉 → 保存并还原 previous
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
