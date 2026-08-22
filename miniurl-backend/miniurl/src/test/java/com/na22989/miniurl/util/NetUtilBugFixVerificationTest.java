package com.na22989.miniurl.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 验证 NetUtil 修复效果（Week 4 Day 6 安全加固后回归）
 *
 * <p>旧版测试 mock remoteAddr="192.0.2.1"（非可信）在新安全模型下会被视为"非可信源忽略代理头"，
 * 因此本类改为：可信直连（127.0.0.1）验证多级代理提取，另用非可信源验证伪造头被忽略。</p>
 */
class NetUtilBugFixVerificationTest {

    @AfterEach
    void resetStaticConfig() {
        // 静态全局状态必须在每个用例后还原，避免污染后续用例
        NetUtil.setTrustedProxies(Set.of());
        NetUtil.setStrictMode(false);
    }

    @Test
    @DisplayName("✅ 修复验证：可信代理下多级 XFF 正确提取 IP（proxiesToTrust=0）")
    void testMultiProxyFixed_TrustLevel0() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);

        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");  // 可信直连
        when(mockRequest.getHeader("X-Forwarded-For"))
                .thenReturn("203.0.113.45, 198.51.100.1, 127.0.0.1");

        // proxiesToTrust=0 → 取最右侧（直连本服务的代理）
        assertEquals("127.0.0.1", NetUtil.getIpAddress(mockRequest, 0), "应正确提取最右侧 IP");
    }

    @Test
    @DisplayName("✅ 修复验证：可信代理下多级 XFF 正确提取 IP（proxiesToTrust=1）")
    void testMultiProxyFixed_TrustLevel1() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);

        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(mockRequest.getHeader("X-Forwarded-For"))
                .thenReturn("203.0.113.45, 198.51.100.1, 127.0.0.1");

        // proxiesToTrust=1 → 跳过最后 1 层，取倒数第 2 个
        assertEquals("198.51.100.1", NetUtil.getIpAddress(mockRequest, 1), "应正确提取倒数第 2 个 IP");
    }

    @Test
    @DisplayName("✅ 修复验证：可信代理下多级 XFF 正确提取客户端 IP（proxiesToTrust=2）")
    void testMultiProxyFixed_TrustLevel2() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);

        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(mockRequest.getHeader("X-Forwarded-For"))
                .thenReturn("203.0.113.45, 198.51.100.1, 127.0.0.1");

        // proxiesToTrust=2 → 跳过最后 2 层，取客户端 IP
        assertEquals("203.0.113.45", NetUtil.getIpAddress(mockRequest, 2), "应正确提取客户端 IP");
    }

    @Test
    @DisplayName("✅ 兼容性：可信代理下单 IP 场景依然正常")
    void testSingleIpStillWorks() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);

        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(mockRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.45");  // 单个 IP

        assertEquals("203.0.113.45", NetUtil.getIpAddress(mockRequest, 0), "单 IP 场景应继续正常工作");
    }

    @Test
    @DisplayName("✅ 边界：X-Forwarded-For 包含空格（可信代理下）")
    void testXForwardedForWithSpaces() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);

        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(mockRequest.getHeader("X-Forwarded-For"))
                .thenReturn(" 203.0.113.45 , 198.51.100.1 , 127.0.0.1 ");

        assertEquals("203.0.113.45", NetUtil.getIpAddress(mockRequest, 2), "应正确处理空格");
    }

    @Test
    @DisplayName("🛡 安全核心：非可信源伪造 X-Forwarded-For → 忽略头，返回直连 IP")
    void testUntrustedSource_ignoresSpoofedXff() {
        HttpServletRequest mockRequest = Mockito.mock(HttpServletRequest.class);

        // 攻击者直连 app（没经过代理），同时塞了伪造的 XFF
        when(mockRequest.getRemoteAddr()).thenReturn("192.0.2.1");  // 非可信直连
        when(mockRequest.getHeader("X-Forwarded-For"))
                .thenReturn("203.0.113.45, 198.51.100.1, 192.0.2.1");

        // 必须返回真实的直连 IP，而不是头里的任何值
        assertEquals("192.0.2.1", NetUtil.getIpAddress(mockRequest, 0), "非可信源必须忽略伪造头");
    }
}
