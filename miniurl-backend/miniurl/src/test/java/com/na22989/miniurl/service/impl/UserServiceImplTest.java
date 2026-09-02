package com.na22989.miniurl.service.impl;

import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.exception.BizException;
import com.na22989.miniurl.mapper.UserMapper;
import com.na22989.miniurl.model.dto.user.LoginRequest;
import com.na22989.miniurl.model.dto.user.RefreshTokenRequest;
import com.na22989.miniurl.model.dto.user.RegisterRequest;
import com.na22989.miniurl.model.entity.User;
import com.na22989.miniurl.model.vo.user.LoginUserVO;
import com.na22989.miniurl.model.vo.user.RefreshTokenVO;
import com.na22989.miniurl.model.vo.user.UserVO;
import com.na22989.miniurl.monitor.MetricsRecorder;
import com.na22989.miniurl.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl 单元测试")
class UserServiceImplTest {

    // 与 jwt.refresh-expiration 对齐（7 天），供 logout 黑名单 TTL 断言使用
    private static final long REFRESH_EXPIRATION = 604800000L;

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private MetricsRecorder recorder;

    private UserServiceImpl userService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;
    private User mockUser;

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor → UserServiceImpl(JwtUtil, PasswordEncoder, StringRedisTemplate, MetricsRecorder)
        userService = new UserServiceImpl(jwtUtil, passwordEncoder, stringRedisTemplate, recorder);
        // 注入父类 ServiceImpl 的 baseMapper（未通过构造器注入）
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        // logout 的 TTL 依赖该 @Value 字段（非 Spring 环境下需手动注入）
        ReflectionTestUtils.setField(userService, "refreshExpiration", REFRESH_EXPIRATION);
        // lenient：register/login/getCurrentUserInfo 不走 Redis，避免 strict stubs 误报
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        registerRequest = new RegisterRequest();
        registerRequest.setUsername("testuser");
        registerRequest.setPassword("123456");
        registerRequest.setNickname("测试");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("123456");

        mockUser = new User()
                .setId(1L)
                .setUsername("testuser")
                .setPassword("$2a$10$encrypted_password_hash")
                .setNickname("测试");
    }

    // ─── register ───

    @Test
    @DisplayName("register() 正常注册应返回 UserVO")
    void register_shouldReturnUserVO() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("123456")).thenReturn("$2a$10$encrypted_password_hash");
        when(userMapper.insert(isA(User.class))).thenReturn(1);

        UserVO result = userService.register(registerRequest);

        assertNotNull(result);
        assertEquals("testuser", result.getUsername());
        assertEquals("测试", result.getNickname());
    }

    @Test
    @DisplayName("register() 重复用户名应抛出 USERNAME_EXISTS")
    void register_shouldThrowWhenUsernameExists() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> userService.register(registerRequest));
        assertEquals(ResultCodeEnum.USERNAME_EXISTS.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("register() 无昵称时应自动生成默认昵称")
    void register_shouldGenerateDefaultNicknameWhenBlank() {
        registerRequest.setNickname(null);

        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
        when(userMapper.insert(isA(User.class))).thenReturn(1);

        UserVO result = userService.register(registerRequest);

        assertNotNull(result.getNickname());
        assertTrue(result.getNickname().startsWith("user_"),
                "默认昵称应以 user_ 开头，实际: " + result.getNickname());
    }

    // ─── login ───

    @Test
    @DisplayName("login() 正常登录应返回 accessToken + refreshToken + 用户信息")
    void login_shouldReturnTokensAndUserInfo() {
        when(userMapper.selectOne(any(), anyBoolean())).thenReturn(mockUser);
        when(passwordEncoder.matches("123456", mockUser.getPassword())).thenReturn(true);
        when(jwtUtil.generateRefreshToken(1L)).thenReturn("fake-refresh-token");
        when(jwtUtil.generateAccessToken(1L)).thenReturn("fake-access-token");

        LoginUserVO result = userService.login(loginRequest);

        assertNotNull(result);
        assertEquals("fake-access-token", result.getAccessToken());
        assertEquals("fake-refresh-token", result.getRefreshToken());
        assertNotNull(result.getUserInfo());
        assertEquals("testuser", result.getUserInfo().getUsername());
    }

    @Test
    @DisplayName("login() 用户不存在应抛出 USER_NOT_FOUND")
    void login_shouldThrowWhenUserNotFound() {
        when(userMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> userService.login(loginRequest));
        assertEquals(ResultCodeEnum.USER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("login() 密码错误应抛出 PASSWORD_ERROR，且不生成 token")
    void login_shouldThrowWhenPasswordWrong() {
        when(userMapper.selectOne(any(), anyBoolean())).thenReturn(mockUser);
        when(passwordEncoder.matches("123456", mockUser.getPassword())).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> userService.login(loginRequest));
        assertEquals(ResultCodeEnum.PASSWORD_ERROR.getCode(), ex.getCode());

        verify(jwtUtil, never()).generateAccessToken(anyLong());
        verify(jwtUtil, never()).generateRefreshToken(anyLong());
    }

    // ─── getCurrentUserInfo ───

    @Test
    @DisplayName("getCurrentUserInfo() 正常应返回 UserVO")
    void getCurrentUserInfo_shouldReturnUserVO() {
        when(userMapper.selectById(1L)).thenReturn(mockUser);

        UserVO result = userService.getCurrentUserInfo(1L);

        assertNotNull(result);
        assertEquals("testuser", result.getUsername());
    }

    @Test
    @DisplayName("getCurrentUserInfo() 用户不存在应抛出 USER_NOT_FOUND")
    void getCurrentUserInfo_shouldThrowWhenUserNotFound() {
        when(userMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> userService.getCurrentUserInfo(999L));
        assertEquals(ResultCodeEnum.USER_NOT_FOUND.getCode(), ex.getCode());
    }

    // ─── logout ───

    @Test
    @DisplayName("logout() 应把 userId 写入黑名单，TTL 与 refresh-expiration 对齐")
    void logout_shouldAddToBlacklistWithRefreshTtl() {
        userService.logout(1L);

        verify(valueOperations).set(
                eq("jwt:blacklist:1"),
                anyString(),
                eq(REFRESH_EXPIRATION),
                eq(TimeUnit.MILLISECONDS));
    }

    // ─── refreshToken ───

    @Test
    @DisplayName("refreshToken() 有效 token 应返回新 accessToken")
    void refreshToken_shouldReturnNewAccessToken() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");

        when(jwtUtil.validateToken("valid-refresh-token", "refresh")).thenReturn(true);
        when(jwtUtil.extractUserId("valid-refresh-token")).thenReturn(1L);
        when(valueOperations.get("jwt:blacklist:1")).thenReturn(null); // 未拉黑
        when(jwtUtil.generateAccessToken(1L)).thenReturn("new-access-token");

        RefreshTokenVO result = userService.refreshToken(request);

        assertNotNull(result);
        assertEquals("new-access-token", result.getAccessToken());
    }

    @Test
    @DisplayName("refreshToken() 无效 token 应抛出 REFRESH_TOKEN_INVALID")
    void refreshToken_shouldThrowWhenInvalid() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("invalid-token");

        when(jwtUtil.validateToken("invalid-token", "refresh")).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> userService.refreshToken(request));
        assertEquals(ResultCodeEnum.REFRESH_TOKEN_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("refreshToken() token 已注销（黑名单晚于签发）应抛出 TOKEN_REVOKED")
    void refreshToken_shouldThrowWhenRevoked() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("revoked-refresh-token");

        when(jwtUtil.validateToken("revoked-refresh-token", "refresh")).thenReturn(true);
        when(jwtUtil.extractUserId("revoked-refresh-token")).thenReturn(1L);
        when(jwtUtil.extractIssuedAt("revoked-refresh-token")).thenReturn(new Date(0L)); // 很早签发
        when(valueOperations.get("jwt:blacklist:1"))
                .thenReturn(String.valueOf(System.currentTimeMillis())); // 之后拉黑

        BizException ex = assertThrows(BizException.class,
                () -> userService.refreshToken(request));
        assertEquals(ResultCodeEnum.TOKEN_REVOKED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("refreshToken() 黑名单早于签发时间不应视为注销，正常换发")
    void refreshToken_shouldNotRevokeWhenBlacklistBeforeIssue() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");

        when(jwtUtil.validateToken("valid-refresh-token", "refresh")).thenReturn(true);
        when(jwtUtil.extractUserId("valid-refresh-token")).thenReturn(1L);
        // 黑名单时间早于签发时间（例如更早登录的旧注销记录），不应误伤
        when(valueOperations.get("jwt:blacklist:1")).thenReturn("1000");
        when(jwtUtil.extractIssuedAt("valid-refresh-token")).thenReturn(new Date(999999999999L));
        when(jwtUtil.generateAccessToken(1L)).thenReturn("new-access-token");

        RefreshTokenVO result = userService.refreshToken(request);
        assertEquals("new-access-token", result.getAccessToken());
    }

    @Test
    @DisplayName("refreshToken() Redis 黑名单读取失败 → fail-open 按未拉黑换发新 token，并打 blacklist_get 指标")
    void refreshToken_shouldFailOpenWhenRedisDown() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");

        when(jwtUtil.validateToken("valid-refresh-token", "refresh")).thenReturn(true);
        when(jwtUtil.extractUserId("valid-refresh-token")).thenReturn(1L);
        when(valueOperations.get("jwt:blacklist:1"))
                .thenThrow(new RuntimeException("Redis 故障"));
        when(jwtUtil.generateAccessToken(1L)).thenReturn("new-access-token");

        RefreshTokenVO result = userService.refreshToken(request);

        assertNotNull(result);
        assertEquals("new-access-token", result.getAccessToken());
        // 降级需可观测：fail-open 的窗口代价是已拉黑 token 可复用，指标便于后续评估
        verify(recorder).recordRedisDegraded("blacklist_get");
    }
}
