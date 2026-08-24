package com.na22989.miniurl.filter;

import com.na22989.miniurl.util.TraceIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static com.na22989.miniurl.common.TraceConstant.TRACE_ID;
import static com.na22989.miniurl.common.TraceConstant.TRACE_ID_ATTR;
import static com.na22989.miniurl.common.TraceConstant.TRACE_ID_HEADER;

/**
 * 全链路 TraceId 注入过滤器：给每个请求发一个 traceId 并注入 MDC，使同一次请求在拦截器、
 * Controller、异步线程、全局异常处理器里的日志能用一个 ID 串起来。
 * <p>
 * 为什么是 Filter 而不是 HandlerInterceptor：
 * <ul>
 *   <li>MDC 清理靠 finally 语言级保证——HandlerInterceptor 的 afterCompletion 只在
 *       preHandle 返回 true 后才被调，本项目限流/鉴权拦截器会 return false，存在真实
 *       泄漏窗口（traceId 串到下一个请求，日志撒谎）；</li>
 *   <li>覆盖「全路径」——Filter 在 DispatcherServlet 之前，天然包含 /actuator/**
 *       （独立 HandlerMapping，不吃 addInterceptors）与 404、ERROR 转发。</li>
 * </ul>
 * 入站 X-Request-Id 仅用于日志关联，不可信，禁止用于任何鉴权/限流判定。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // ERROR 转发是同一 request 的第二次派发：第一次的 MDC 已清，但 request attribute 仍存活，
        // 复用同一 traceId 才能让 /error 的日志与业务日志保持同号
        String traceId = (String) request.getAttribute(TRACE_ID_ATTR);
        if (traceId == null) {
            traceId = TraceIdUtil.resolve(request.getHeader(TRACE_ID_HEADER));
            request.setAttribute(TRACE_ID_ATTR, traceId);
        }

        MDC.put(TRACE_ID, traceId);

        // 必须在 chain 之前：限流/未登录拦截器会直写 JSON 提交响应，之后 setHeader 被静默丢弃，
        // 而 429/401 恰恰最需要 traceId
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat 线程池复用：不清则 traceId 串到下一个请求，日志会撒谎
            MDC.clear();
        }
    }

    /** 默认 true 会跳过 ERROR 转发；拦截器抛的异常正是走容器 ERROR 转发，必须覆盖为 false */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
