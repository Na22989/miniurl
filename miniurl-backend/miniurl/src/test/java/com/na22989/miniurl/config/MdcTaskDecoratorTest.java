package com.na22989.miniurl.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.na22989.miniurl.common.TraceConstant.TRACE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MdcTaskDecorator 单元测试。
 * <p>
 * 验证跨线程 MDC 搬运的三条契约：异步线程可见提交线程的 traceId（点击计数日志能回溯请求）、
 * 长驻线程执行完必须还原、CallerRunsPolicy 回压时不能抹掉请求线程自己的 traceId（本项目
 * 特有陷阱，拒绝策略正是 CallerRunsPolicy）。
 */
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void tearDown() {
        // MDC 是 ThreadLocal，JUnit 同线程串跑，一个用例的残留会污染后续用例断言
        MDC.clear();
    }

    @Test
    @DisplayName("提交线程的 traceId 在异步线程内可见")
    void traceId_visibleInAsyncThread() throws Exception {
        MDC.put(TRACE_ID, "trace-request-0001");
        AtomicReference<String> captured = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> captured.set(MDC.get(TRACE_ID)));

        Thread t = new Thread(decorated);
        t.start();
        t.join();

        assertEquals("trace-request-0001", captured.get(), "异步日志必须能回溯触发它的请求，否则点击计数链路断在异步边界");
    }

    @Test
    @DisplayName("异步任务执行完新线程 MDC 被还原为空")
    void asyncThread_mdcRestoredEmpty() throws Exception {
        MDC.put(TRACE_ID, "trace-request-0002");
        Runnable decorated = decorator.decorate(() -> { /* 任务体不操作 MDC */ });
        AtomicReference<Map<String, String>> afterRun = new AtomicReference<>();

        Thread t = new Thread(() -> {
            decorated.run();
            afterRun.set(MDC.getCopyOfContextMap());
        });
        t.start();
        t.join();

        assertNull(afterRun.get(), "core=4 的长驻线程一次污染会长期携带旧 ID，执行完必须还原为空");
    }

    @Test
    @DisplayName("同线程执行（CallerRunsPolicy 回压）后调用方 MDC 不丢")
    void callerThread_mdcPreserved() {
        MDC.put(TRACE_ID, "caller-trace-0001");
        Runnable decorated = decorator.decorate(() -> { /* 任务体不操作 MDC */ });

        decorated.run();

        assertEquals("caller-trace-0001", MDC.get(TRACE_ID),
                "回压时执行线程就是请求线程，无脑 clear 会抹掉请求自己的 traceId");
    }

    @Test
    @DisplayName("提交线程无 MDC 时任务不抛 NPE")
    void noMdc_submitThread_noNpe() {
        MDC.clear();
        AtomicReference<Map<String, String>> inTask = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> inTask.set(MDC.getCopyOfContextMap()));

        decorated.run();

        assertNull(inTask.get(), "提交线程无 MDC（captured=null）时任务内 MDC 为空且不抛 NPE，定时任务/启动期提交不能被拖垮");
    }
}
