package com.na22989.miniurl.util;

import com.na22989.miniurl.model.dto.link.AccessMeta;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * getAccessMeta 专项测试（重定向访问日志元数据）
 *
 * <p>覆盖 NetUtil.getAccessMeta：IP 走安全解析（委托 getIpAddress）+ User-Agent / Referer
 * 超长截断（防 DB 列溢出）+ 缺省值。</p>
 */
class NetUtilAccessMetaTest {

    @AfterEach
    void resetStaticConfig() {
        // 静态全局状态还原，保持用例隔离（与 NetUtil 其他测试类一致）
        NetUtil.setTrustedProxies(Set.of());
        NetUtil.setStrictMode(false);
    }

    @Test
    @DisplayName("✅ getAccessMeta(null) → 默认访问元数据")
    void getAccessMeta_nullRequest_returnsDefaults() {
        AccessMeta meta = NetUtil.getAccessMeta(null, 0);

        assertEquals("127.0.0.1", meta.getIp());
        assertEquals("unknown", meta.getUserAgent());
        assertEquals("", meta.getReferer());
    }

    @Test
    @DisplayName("✅ 正常请求 → IP 走安全解析，User-Agent / Referer 原样透传")
    void getAccessMeta_normalRequest() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.99"); // 非可信直连
        when(req.getHeader("User-Agent")).thenReturn("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        when(req.getHeader("Referer")).thenReturn("https://example.com/page");

        AccessMeta meta = NetUtil.getAccessMeta(req, 0);

        assertEquals("203.0.113.99", meta.getIp());
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", meta.getUserAgent());
        assertEquals("https://example.com/page", meta.getReferer());
    }

    @Test
    @DisplayName("✅ 缺少 User-Agent / Referer → 默认值")
    void getAccessMeta_missingHeaders_returnsDefaults() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.99");

        AccessMeta meta = NetUtil.getAccessMeta(req, 0);

        assertEquals("203.0.113.99", meta.getIp());
        assertEquals("unknown", meta.getUserAgent());
        assertEquals("", meta.getReferer());
    }

    @Test
    @DisplayName("🛡 User-Agent 超长 → 截断到 450 字符（防 DB 列溢出）")
    void getAccessMeta_longUserAgent_truncatedTo450() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getHeader("User-Agent")).thenReturn("A".repeat(1000));

        AccessMeta meta = NetUtil.getAccessMeta(req, 0);

        assertEquals(450, meta.getUserAgent().length());
        assertEquals("A".repeat(450), meta.getUserAgent());
    }

    @Test
    @DisplayName("🛡 Referer 超长 → 截断到 2000 字符（防 DB 列溢出）")
    void getAccessMeta_longReferer_truncatedTo2000() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getHeader("Referer")).thenReturn("B".repeat(3000));

        AccessMeta meta = NetUtil.getAccessMeta(req, 0);

        assertEquals(2000, meta.getReferer().length());
        assertEquals("B".repeat(2000), meta.getReferer());
    }

    @Test
    @DisplayName("✅ 委托安全模型：可信代理 + X-Real-IP → 访问日志 IP 取 X-Real-IP")
    void getAccessMeta_trustedProxy_usesXRealIp() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1"); // 回环默认可信
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.7");

        AccessMeta meta = NetUtil.getAccessMeta(req, 0);

        assertEquals("198.51.100.7", meta.getIp(),
                "访问日志 IP 必须沿用安全模型解析结果");
    }
}
