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
        when(req.getHeader("X-Real-IP")).thenReturn("192.0.2.8"); // 伪造头（RFC 5737 文档段）

        assertEquals("203.0.113.99", NetUtil.getIpAddress(req, 0),
                "非可信源设置的 X-Real-IP 必须被忽略");
    }

    @Test
    @DisplayName("🛡 P0#1：非可信源伪造 X-Forwarded-For → 忽略头")
    void untrustedSource_shouldIgnoreSpoofedXff() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.99");
        when(req.getHeader("X-Forwarded-For")).thenReturn("192.0.2.4");

        assertEquals("203.0.113.99", NetUtil.getIpAddress(req, 0),
                "非可信源设置的 X-Forwarded-For 必须被忽略");
    }

    @Test
    @DisplayName("🛡 严格模式：非可信源伪造 → 抛 SecurityException")
    void strictMode_shouldRejectSpoofedHeaders() {
        NetUtil.setStrictMode(true);
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.99");
        when(req.getHeader("X-Real-IP")).thenReturn("192.0.2.8");

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

    // ─── 缺口1：可信源双头并存 → X-Real-IP 必须优先（防 XFF 前缀污染）───

    @Test
    @DisplayName("✅ 可信代理同时带 X-Real-IP 与 X-Forwarded-For → X-Real-IP 优先")
    void trustedProxy_xRealIpTakesPrecedenceOverXff() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        // nginx 追加 XFF（含攻击者塞进的前缀），同时设 X-Real-IP = 真实客户端
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.7");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.99, 198.51.100.7, 127.0.0.1");

        // 即使 proxiesToTrust=2 会恰好指向攻击者前缀，X-Real-IP 也必须抢先被采信
        assertEquals("198.51.100.7", NetUtil.getIpAddress(req, 2),
                "X-Real-IP 必须在 X-Forwarded-For 之前被采信");
    }

    // ─── 缺口2：可信源无效头 → 信任阶梯逐级兜底 ───

    @Test
    @DisplayName("✅ 可信代理 X-Real-IP 无效 → 落到 X-Forwarded-For")
    void trustedProxy_invalidXRealIp_fallsBackToXff() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("X-Real-IP")).thenReturn("not-an-ip");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.45, 198.51.100.1, 127.0.0.1");

        assertEquals("198.51.100.1", NetUtil.getIpAddress(req, 1),
                "无效 X-Real-IP 应被跳过，继续走 XFF 提取");
    }

    @Test
    @DisplayName("✅ 可信代理 X-Forwarded-For 为 unknown → 忽略并落兜底直连 IP")
    void trustedProxy_xffUnknown_fallsBackToRemoteAddr() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn("unknown");

        assertEquals("127.0.0.1", NetUtil.getIpAddress(req, 0),
                "XFF 为 unknown 时整段跳过，兜底直连 IP");
    }

    @Test
    @DisplayName("✅ 可信代理未带任何代理头 → 兜底直连 IP")
    void trustedProxy_noHeaders_returnsRemoteAddr() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("127.0.0.1", NetUtil.getIpAddress(req, 0),
                "无代理头时可信源也回退到直连 IP");
    }

    @Test
    @DisplayName("✅ 可信代理仅带历史头 Proxy-Client-IP → 采信该头")
    void trustedProxy_fallbackLegacyHeader() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("Proxy-Client-IP")).thenReturn("198.51.100.7");

        assertEquals("198.51.100.7", NetUtil.getIpAddress(req, 0),
                "X-Real-IP 与 XFF 都缺时，历史代理头应被采信");
    }

    // ─── 缺口4：strict-mode 只拒非可信伪造，不误伤正常链路 ───

    @Test
    @DisplayName("✅ 严格模式下可信代理设头 → 不抛异常，正常采信")
    void strictMode_trustedProxy_stillWorks() {
        NetUtil.setStrictMode(true);
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.7");

        assertEquals("198.51.100.7", NetUtil.getIpAddress(req, 0),
                "strict-mode 只拒非可信源伪造，可信代理链路必须不受影响");
    }

    @Test
    @DisplayName("✅ 严格模式下非可信源但无代理头 → 不抛异常（只拒伪造行为）")
    void strictMode_cleanUntrustedRequest_noThrow() {
        NetUtil.setStrictMode(true);
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.99"); // 非可信直连，但没伪造头

        assertEquals("203.0.113.99", NetUtil.getIpAddress(req, 0),
                "strict-mode 只拦截伪造行为，干净的未知来源请求应放行");
    }
}
