package com.na22989.miniurl.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * URL 安全校验工具：输入校验（创建短链）与输出校验（重定向）共用同一套规则。
 * <p>
 * 安全标准：仅允许 http/https 协议 + 有非空 host + 不含控制字符。
 * 其余协议（javascript: / data: / vbscript: / file: 等）一律拒绝，防止重定向型 XSS。
 */
public final class UrlSecurityUtil {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private UrlSecurityUtil() {
    }

    /**
     * 判断是否为安全的 http/https URL。
     *
     * @param url 待校验的 URL
     * @return true 表示可安全用于重定向
     */
    public static boolean isSafeHttpUrl(String url) {
        if (url == null) {
            return false;
        }

        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        // 1. 拒绝控制字符（\r \n \t 等），防止响应头注入 / 协议混淆
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c <= 0x1F || c == 0x7F) {
                return false;
            }
        }

        try {
            // 使用 URI 而非 URL：避免 DNS 解析与协议处理器查找
            URI uri = new URI(trimmed);

            // 2. 白名单：只允许 http/https（其余 javascript:/data:/file:/vbscript: 等一律拒绝）
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                return false;
            }

            // 3. 必须有非空 host（防止 "http://" 空壳）
            String host = uri.getHost();
            return host != null && !host.isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
