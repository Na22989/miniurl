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
}
