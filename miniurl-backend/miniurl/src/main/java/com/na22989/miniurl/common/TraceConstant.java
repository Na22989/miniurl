package com.na22989.miniurl.common;

public final class TraceConstant {

    private TraceConstant() {}

    /** MDC key，与 application.yml 的 logging.pattern.correlation 中 %X{traceId} 必须一致 */
    public static final String TRACE_ID = "traceId";

    /** 入站读取 + 出站回写的头名；与 nginx $request_id 约定一致 */
    public static final String TRACE_ID_HEADER = "X-Request-Id";

    /** 存 request attribute，供容器 ERROR 转发二次进入 Filter 时复用同一 traceId */
    public static final String TRACE_ID_ATTR = TraceConstant.class.getName() + ".traceId";
}
