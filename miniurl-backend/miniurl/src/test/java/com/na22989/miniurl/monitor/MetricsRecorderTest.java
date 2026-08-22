package com.na22989.miniurl.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricsRecorder 单元测试")
class MetricsRecorderTest {

    @Mock
    private MeterRegistry registry;

    @Mock
    private Counter counter;

    private MetricsRecorder metricsRecorder;

    @BeforeEach
    void setUp() {
        metricsRecorder = new MetricsRecorder(registry);
        // registry.counter() 在 @Mock 下默认返回 null，必须 stub 返回 counter mock；
        // lenient：部分测试只用其中一种签名，避免 UnnecessaryStubbingException
        lenient().when(registry.counter(anyString())).thenReturn(counter);
        lenient().when(registry.counter(anyString(), anyString(), anyString())).thenReturn(counter);
    }

    @Test
    @DisplayName("recordRedirect() 应按 source 创建带 tag 的 Counter 并自增")
    void recordRedirect_shouldCreateTaggedCounterAndIncrement() {
        metricsRecorder.recordRedirect("l1");

        verify(registry).counter("miniurl.redirect.total", "source", "l1");
        verify(counter).increment();
    }

    @Test
    @DisplayName("recordLinkCreated() 应创建无 tag 的 Counter 并自增")
    void recordLinkCreated_shouldCreatePlainCounterAndIncrement() {
        metricsRecorder.recordLinkCreated();

        verify(registry).counter("miniurl.link.created");
        verify(counter).increment();
    }

    @Test
    @DisplayName("recordRedirectFail() 应按 reason 创建带 tag 的 Counter 并自增")
    void recordRedirectFail_shouldCreateTaggedCounterAndIncrement() {
        metricsRecorder.recordRedirectFail("expired");

        verify(registry).counter("miniurl.redirect.fail", "reason", "expired");
        verify(counter).increment();
    }
}
