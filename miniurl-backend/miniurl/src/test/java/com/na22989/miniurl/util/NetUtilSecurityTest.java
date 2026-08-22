package com.na22989.miniurl.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * NetUtil 安全加固专项验证（Week 4 Day 6）
 *
 * <p>覆盖 SECURITY_AUDIT_NetUtil.md 的三个 P0：X-Real-IP 伪造 / proxiesToTrust 默认值 /
 * 缺少可信代理校验；外加 CIDR 白名单匹配与严格模式。</p>
 */
class NetUtilSecurityTest {

    @AfterEach
    void resetStaticConfig() {
        // 静态全局状态：每个用例后还原为默认（仅回环可信，strictMode 关闭）
        NetUtil.setTrustedProxies(Set.of());
        NetUtil.setStrictMode(false);
    }

    @Test
    @DisplayName("🛡 P0#1：非可信源伪造 X-Real-IP → 忽略头，返回真实直连 IP")
    void untrustedSource_shouldIgnoreSpoofedXRealIp() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.99");  // 攻击者真实 IP
        when(req.getHeader("X-Real-IP")).thenReturn("8.8.8.8"); // 伪造头

        assertEquals("203.0.113.99", NetUtil.getIpAddress(req, 0),
                "非可信源设置的 X-Real-IP 必须被忽略");
    }

    @Test
    @DisplayName("🛡 P0#1：非可信源伪造 X-Forwarded-For → 忽略头")
    void untrustedSource_shouldIgnoreSpoofedXff() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.99");
        when(req.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

        assertEquals("203.0.113.99", NetUtil.getIpAddress(req, 0),
                "非可信源设置的 X-Forwarded-For 必须被忽略");
    }

    @Test
    @DisplayName("🛡 严格模式：非可信源伪造 → 抛 SecurityException")
    void strictMode_shouldRejectSpoofedHeaders() {
        NetUtil.setStrictMode(true);
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.99");
        when(req.getHeader("X-Real-IP")).thenReturn("8.8.8.8");

        assertThrows(SecurityException.class, () -> NetUtil.getIpAddress(req, 0),
                "严格模式下伪造头必须被拒绝");
    }

    @Test
    @DisplayName("✅ 可信代理（精确 127.0.0.1）+ X-Real-IP → 取 X-Real-IP")
    void trustedProxy_shouldUseXRealIp() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.7");

        assertEquals("198.51.100.7", NetUtil.getIpAddress(req, 0),
                "可信代理设置的 X-Real-IP 应被采信");
    }

    @Test
    @DisplayName("✅ 可信代理（CIDR 172.16/12 命中）+ X-Real-IP → 取 X-Real-IP")
    void trustedProxyByCidr_shouldUseXRealIp() {
        NetUtil.setTrustedProxies(Set.of("172.16.0.0/12"));
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("172.19.3.4");   // 命中 172.16.0.0/12
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.7");

        assertEquals("198.51.100.7", NetUtil.getIpAddress(req, 0),
                "命中 CIDR 段应视为可信代理");
    }

    @Test
    @DisplayName("🛡 CIDR 未命中（172.15.0.1）→ 非可信，忽略头")
    void cidrNotMatched_shouldIgnoreHeaders() {
        NetUtil.setTrustedProxies(Set.of("172.16.0.0/12"));
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("172.15.0.1");    // 恰好在 172.16/12 之外
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.7");

        assertEquals("172.15.0.1", NetUtil.getIpAddress(req, 0),
                "CIDR 未命中必须按非可信源处理");
    }

    @Test
    @DisplayName("✅ 可信代理 + 多级 XFF：按 proxiesToTrust 从右向左提取")
    void trustedProxy_multiLevelXff() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.45, 198.51.100.1, 127.0.0.1");

        assertEquals("127.0.0.1", NetUtil.getIpAddress(req, 0));
        assertEquals("198.51.100.1", NetUtil.getIpAddress(req, 1));
        assertEquals("203.0.113.45", NetUtil.getIpAddress(req, 2));
    }

    @Test
    @DisplayName("✅ setTrustedProxies 始终保留回环：传空集合后本地直连仍可信")
    void setTrustedProxies_alwaysKeepsLoopback() {
        NetUtil.setTrustedProxies(Set.of("10.0.0.0/8"));

        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.7");

        // 即使白名单只配了 10/8，回环依然可信（本地直连调试不被误伤）
        assertEquals("198.51.100.7", NetUtil.getIpAddress(req, 0));
    }

    @Test
    @DisplayName("✅ isLocalhost 兼容 IPv6 回环（P1#5 不再强制转 127.0.0.1）")
    void isLocalhost_ipv6Loopback() {
        assertTrue(NetUtil.isLocalhost("127.0.0.1"));
        assertTrue(NetUtil.isLocalhost("::1"));
        assertTrue(NetUtil.isLocalhost("0:0:0:0:0:0:0:1"));
    }
}
