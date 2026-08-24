package com.na22989.miniurl.util;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * TraceId 生成与校验工具。
 * <p>
 * 生成 32 位无横杠小写 hex（与 nginx $request_id 同构，也便于双击选中与 grep）；
 * 入站 X-Request-Id 过白名单校验后复用——白名单封的是 CRLF 日志伪造与长度膨胀两条威胁。
 */
public final class TraceIdUtil {

    /** 入站头白名单：仅 URL-safe 字符，长度 8~64。定长上界防日志膨胀，字符集防 CRLF 注入 */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private TraceIdUtil() {
    }

    /** 生成 32 位无横杠小写 hex。无锁、不抛异常，UUID 写法在 UserServiceImpl 已有先例 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 校验外部传入的 traceId 是否可安全写入日志；null/空/不匹配白名单 → false */
    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        return VALID.matcher(candidate).matches();
    }

    /** 取值入口：入站头合法则复用，否则生成。Filter 与 Phase 2 调度装饰器共用 */
    public static String resolve(String inboundHeaderValue) {
        if (isValid(inboundHeaderValue)) {
            return inboundHeaderValue;
        } else {
            return generate();
        }
    }
}
