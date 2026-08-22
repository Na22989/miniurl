package com.na22989.miniurl.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtUtil 单元测试")
class JwtUtilTest {

    // HS256 至少需要 256 位（32 字节）的 key
    private static final String TEST_SECRET = "this-is-a-test-secret-for-jwt-hs256-minimum-32-bytes!!";
    private static final Long TEST_ACCESS_EXPIRATION = 1800000L;    // 30 分钟
    private static final Long TEST_REFRESH_EXPIRATION = 604800000L; // 7 天

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "accessExpiration", TEST_ACCESS_EXPIRATION);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", TEST_REFRESH_EXPIRATION);
    }

    @Test
    @DisplayName("generateAccessToken() 应返回三段式 JWT")
    void generateAccessToken_shouldReturnThreePartJwt() {
        String token = jwtUtil.generateAccessToken(1L);
        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals(3, token.split("\\.").length, "JWT 应为三段式");
    }

    @Test
    @DisplayName("generateRefreshToken() 应返回三段式 JWT")
    void generateRefreshToken_shouldReturnThreePartJwt() {
        String token = jwtUtil.generateRefreshToken(1L);
        assertNotNull(token);
        assertFalse(token.isBlank());
        assertEquals(3, token.split("\\.").length, "JWT 应为三段式");
    }

    @Test
    @DisplayName("extractUserId() 应正确提取 userId")
    void extractUserId_shouldReturnCorrectUserId() {
        Long userId = 12345L;
        String token = jwtUtil.generateAccessToken(userId);
        assertEquals(userId, jwtUtil.extractUserId(token));
    }

    @Test
    @DisplayName("extractType() 应区分 access 与 refresh")
    void extractType_shouldDistinguishAccessAndRefresh() {
        assertEquals("access", jwtUtil.extractType(jwtUtil.generateAccessToken(1L)));
        assertEquals("refresh", jwtUtil.extractType(jwtUtil.generateRefreshToken(1L)));
    }

    @Test
    @DisplayName("validateToken() 类型匹配应通过")
    void validateToken_shouldPassForMatchingType() {
        assertTrue(jwtUtil.validateToken(jwtUtil.generateAccessToken(1L), "access"));
        assertTrue(jwtUtil.validateToken(jwtUtil.generateRefreshToken(1L), "refresh"));
    }

    @Test
    @DisplayName("validateToken() 类型不匹配应拒绝（access/refresh 隔离）")
    void validateToken_shouldRejectTypeMismatch() {
        assertFalse(jwtUtil.validateToken(jwtUtil.generateAccessToken(1L), "refresh"));
        assertFalse(jwtUtil.validateToken(jwtUtil.generateRefreshToken(1L), "access"));
    }

    @Test
    @DisplayName("validateToken() 篡改签名应返回 false")
    void validateToken_shouldReturnFalseForTamperedToken() {
        String token = jwtUtil.generateAccessToken(1L);
        String[] parts = token.split("\\.");
        parts[2] = "tampered_signature";
        String tampered = String.join(".", parts);
        assertFalse(jwtUtil.validateToken(tampered, "access"));
    }

    @Test
    @DisplayName("validateToken() 空字符串应返回 false")
    void validateToken_shouldReturnFalseForEmptyString() {
        assertFalse(jwtUtil.validateToken("", "access"));
    }

    @Test
    @DisplayName("validateToken() 随机字符串应返回 false")
    void validateToken_shouldReturnFalseForRandomString() {
        assertFalse(jwtUtil.validateToken("not.a.valid.jwt.token", "access"));
    }

    @Test
    @DisplayName("validateToken() 过期 token 应返回 false")
    void validateToken_shouldReturnFalseForExpiredToken() {
        JwtUtil expiredJwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(expiredJwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(expiredJwtUtil, "accessExpiration", -1L); // 立即过期
        ReflectionTestUtils.setField(expiredJwtUtil, "refreshExpiration", TEST_REFRESH_EXPIRATION);

        String token = expiredJwtUtil.generateAccessToken(1L);
        assertFalse(expiredJwtUtil.validateToken(token, "access"), "过期 token 应校验失败");
    }

    @Test
    @DisplayName("不同 userId 生成的 token 提取结果应不同")
    void extractUserId_shouldDifferForDifferentUsers() {
        assertNotEquals(
                jwtUtil.extractUserId(jwtUtil.generateAccessToken(100L)),
                jwtUtil.extractUserId(jwtUtil.generateAccessToken(200L)));
    }
}
