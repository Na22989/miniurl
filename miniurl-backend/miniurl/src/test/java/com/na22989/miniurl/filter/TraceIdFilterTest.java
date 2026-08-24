package com.na22989.miniurl.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static com.na22989.miniurl.common.TraceConstant.TRACE_ID;
import static com.na22989.miniurl.common.TraceConstant.TRACE_ID_ATTR;
import static com.na22989.miniurl.common.TraceConstant.TRACE_ID_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceIdFilter 单元测试。
 * <p>
 * 覆盖两条核心契约：traceId 取用（生成 / 复用 / 白名单拒绝）与 MDC 生命周期（正常与异常
 * 路径都必须清理，防 Tomcat 线程复用串号——这是选 Filter 而非 Interceptor 的核心理由）。
 * <p>
 * 注意：MDC 只在 chain 执行期间存活（Filter 的 finally 会清空），断言必须用 chain 回调
 * 抓值，不能等 doFilter 返回后再读。
 */
class TraceIdFilterTest {

    /** 自生成 traceId 的格式：32 位无横杠小写 hex */
    private static final String UUID_PATTERN = "^[0-9a-f]{32}$";

    /** 空操作链路：只验证 Filter 自身行为，不模拟下游 */
    private static final FilterChain NOOP_CHAIN = (req, res) -> {
    };

    private TraceIdFilter filter;

    private MockHttpServletRequest request;

    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new TraceIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        // MDC 是 ThreadLocal，JUnit 同线程串跑，一个用例的残留会污染后续用例断言
        MDC.clear();
    }

    /** 跑完整次 Filter 并抓取 chain 执行瞬间的 MDC traceId（MDC 在 doFilter 返回后已被清空） */
    private String runAndCaptureTraceId() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> captured.set(MDC.get(TRACE_ID)));
        return captured.get();
    }

    @Test
    @DisplayName("无入站头时生成 32 位 hex traceId")
    void noInboundHeader_generatesTraceId() throws Exception {
        String traceId = runAndCaptureTraceId();

        assertNotNull(traceId, "请求无入站头时必须生成 traceId，否则该请求日志无号可挂");
        assertTrue(traceId.matches(UUID_PATTERN), "生成的 traceId 必须是 32 位小写 hex，与 nginx $request_id 同构");
    }

    @Test
    @DisplayName("合法入站 X-Request-Id 原样复用")
    void validInboundHeader_reused() throws Exception {
        request.addHeader(TRACE_ID_HEADER, "inbound-valid-id-001");

        String traceId = runAndCaptureTraceId();

        assertEquals("inbound-valid-id-001", traceId,
                "入站合法 ID 必须复用，否则与 nginx / 上游日志无法对拼");
    }

    @Test
    @DisplayName("入站含 CRLF 被拒绝并回退自生成")
    void crlfInbound_rejectedAndRegenerated() throws Exception {
        request.addHeader(TRACE_ID_HEADER, "aaa\r\nFAKE LOG");

        String traceId = runAndCaptureTraceId();

        assertNotEquals("aaa\r\nFAKE LOG", traceId, "CRLF 必须被拒，否则可伪造整行日志污染审计");
        assertTrue(traceId.matches(UUID_PATTERN), "CRLF 被拒后必须回退为自生成的 32 hex");
    }

    @Test
    @DisplayName("入站超长（65 字符）被拒绝")
    void tooLongInbound_rejected() throws Exception {
        request.addHeader(TRACE_ID_HEADER, "a".repeat(65));

        String traceId = runAndCaptureTraceId();

        assertTrue(traceId.matches(UUID_PATTERN),
                "不限长等于让单个头膨胀每条日志（变相 DoS），必须拒绝并回退自生成");
    }

    @Test
    @DisplayName("入站过短（1 字符）被拒绝")
    void tooShortInbound_rejected() throws Exception {
        request.addHeader(TRACE_ID_HEADER, "1");

        String traceId = runAndCaptureTraceId();

        assertTrue(traceId.matches(UUID_PATTERN),
                "无区分度的 ID 复用后不如自生成，必须拒绝");
    }

    @Test
    @DisplayName("入站含空格或中文被拒绝")
    void nonUrlSafeInbound_rejected() throws Exception {
        request.addHeader(TRACE_ID_HEADER, "含空格 id");

        String traceId = runAndCaptureTraceId();

        assertTrue(traceId.matches(UUID_PATTERN),
                "非 URL-safe 字符必须拒绝，字符集白名单是封 CRLF 注入的安全边界");
    }

    @Test
    @DisplayName("响应头 X-Request-Id 回写且等于 MDC 值")
    void responseHeader_matchesMdc() throws Exception {
        request.addHeader(TRACE_ID_HEADER, "echo-me-12345678");

        String traceId = runAndCaptureTraceId();

        assertEquals(traceId, response.getHeader(TRACE_ID_HEADER),
                "客户端凭响应头自助报障，回写值必须等于日志中的 traceId");
    }

    @Test
    @DisplayName("响应头在 chain.doFilter 之前写入")
    void responseHeader_writtenBeforeChain() throws Exception {
        // 在 doFilter 回调内断言：响应头此刻必须已存在
        FilterChain assertingChain = (req, res) -> {
            String header = response.getHeader(TRACE_ID_HEADER);
            assertNotNull(header, "响应头必须在 chain 之前写，限流/未登录直写 JSON 会提交响应，写晚了被静默丢弃");
        };

        filter.doFilter(request, response, assertingChain);
    }

    @Test
    @DisplayName("chain 正常返回后 MDC 已清理")
    void mdcCleared_afterNormalChain() throws Exception {
        filter.doFilter(request, response, NOOP_CHAIN);

        assertNull(MDC.get(TRACE_ID), "Tomcat 线程复用，不清则 traceId 串到下一个请求");
    }

    @Test
    @DisplayName("chain 抛异常后 MDC 仍已清理")
    void mdcCleared_afterException() throws Exception {
        FilterChain throwingChain = (req, res) -> {
            throw new IOException("boom");
        };

        assertThrows(IOException.class, () -> filter.doFilter(request, response, throwingChain),
                "下游异常必须向上传播，不能吞掉");
        assertNull(MDC.get(TRACE_ID), "异常路径也必须清理——这是选 Filter 而非 Interceptor 的核心理由，回归即失去泄漏防线");
    }

    @Test
    @DisplayName("ERROR 转发二次进入时复用同一 traceId")
    void errorDispatch_reusesSameTraceId() throws Exception {
        request.setAttribute(TRACE_ID_ATTR, "fixed-id-value");

        String traceId = runAndCaptureTraceId();

        assertEquals("fixed-id-value", traceId,
                "同一请求的 /error 转发必须与业务日志同 ID，否则异常链断开");
    }
}
