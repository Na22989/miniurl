package com.na22989.miniurl.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 多级代理场景回归（原 MultiProxyBug 已修复，Week 4 Day 6 改为可信代理门禁）
 */
class NetUtilMultiProxyBugTest {

    @AfterEach
    void resetStaticConfig() {
        // 静态全局状态必须还原
        NetUtil.setTrustedProxies(Set.of());
        NetUtil.setStrictMode(false);
    }

    @Test
    @DisplayName("✅ 已修复：可信源多级代理按 proxiesToTrust 正确提取")
    void testMultiProxyFixed_Trusted() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);

        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");  // 可信直连
        when(mockRequest.getHeader("X-Forwarded-For"))
                .thenReturn("203.0.113.45, 198.51.100.1, 127.0.0.1");
        // 客户端: 203.0.113.45
        // CDN:     198.51.100.1
        // Nginx:   127.0.0.1（直连）

        assertEquals("127.0.0.1", NetUtil.getIpAddress(mockRequest, 0), "proxiesToTrust=0 取最右");
        assertEquals("198.51.100.1", NetUtil.getIpAddress(mockRequest, 1), "proxiesToTrust=1 穿透一层");
        assertEquals("203.0.113.45", NetUtil.getIpAddress(mockRequest, 2), "proxiesToTrust=2 取客户端");
    }

    @Test
    @DisplayName("✅ 已修复：可信源单级代理正常工作")
    void testSingleProxyWorks() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);

        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(mockRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.45");

        assertEquals("203.0.113.45", NetUtil.getIpAddress(mockRequest, 0), "单 IP 时应正常工作");
    }

    @Test
    @DisplayName("🔬 InetAddresses.isInetAddress：多 IP 逗号串不是合法 IP（先切分再校验的根源）")
    void testInetAddressBehavior() {
        assertTrue(com.google.common.net.InetAddresses.isInetAddress("203.0.113.45"));
        assertFalse(com.google.common.net.InetAddresses.isInetAddress("203.0.113.45, 198.51.100.1"),
                "整个 XFF 逗号串不是合法 IP → 提取前必须先 split 逐个校验");
    }

    // ─── 缺口3：extractIpFromForwardedFor 边界分支 ───

    @Test
    @DisplayName("✅ 边界：proxiesToTrust 超过实际层数 → 取最左客户端 IP")
    void testProxiesToTrustExceedsIpCount() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);
        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(mockRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.45, 198.51.100.1");

        // 只有 2 个 IP，proxiesToTrust=5 → 超界 → 取最左客户端
        assertEquals("203.0.113.45", NetUtil.getIpAddress(mockRequest, 5),
                "配置超过实际代理层数时取最左客户端 IP");
    }

    @Test
    @DisplayName("✅ 边界：proxiesToTrust 为负数 → 重置为 0 取最右")
    void testNegativeProxiesToTrust() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);
        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(mockRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.45, 198.51.100.1, 127.0.0.1");

        // 负数 → 重置 0 → 取最右（直连本服务的代理）
        assertEquals("127.0.0.1", NetUtil.getIpAddress(mockRequest, -1),
                "负数层级应重置为 0");
    }
}
