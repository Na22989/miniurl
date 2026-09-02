package com.na22989.miniurl.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GlobalRateLimitInterceptor 单元测试。
 * <p>
 * 覆盖两条关键契约：Lua 决策（放行/拒绝/降级）正确透传，拒绝路径以 429 + Retry-After
 * 直写 JSON（拦截器异常不被 @RestControllerAdvice 捕获，必须自行写响应）。fail-open 场景
 * 验证「限流组件故障不影响业务」这一降级语义，防止未来改动破坏该契约。
 */
@ExtendWith(MockitoExtension.class)
class GlobalRateLimitInterceptorTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisScript<List<Long>> rateLimitScript;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GlobalRateLimitInterceptor interceptor;

    private MockHttpServletRequest request;

    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new GlobalRateLimitInterceptor(stringRedisTemplate, objectMapper, rateLimitScript);
        // @Value 字段在非 Spring 环境不会自动注入，手动注入默认配置（启用，100 req/s）
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        ReflectionTestUtils.setField(interceptor, "tokensPerSecond", 100);
        ReflectionTestUtils.setField(interceptor, "maxTokens", 100);
        // rateLimitScript 由 RedisLuaScripts @Bean 注入（Bean 加载即 fail-fast），此处 mock 仅占位
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("禁用限流时直接放行，不触 Redis")
    void disabled_shouldPassThrough_withoutRedis() throws Exception {
        ReflectionTestUtils.setField(interceptor, "enabled", false);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result, "关闭开关后必须放行，否则限流功能不可关闭");
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("Lua 返回放行（剩余令牌 99）时放行")
    void luaAllowed_shouldPassThrough() throws Exception {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(1L, 99L));

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result, "Lua 返回 allowed=1 时必须放行，否则正常创建短链会被误伤");
    }

    @Test
    @DisplayName("Lua 返回拒绝时返回 429 + Retry-After，body 为 RATE_LIMIT 错误码")
    void luaDenied_shouldWrite429_andRetryAfter() throws Exception {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(0L, 0L));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result, "Lua 返回 allowed=0 时必须拒绝");
        assertEquals(429, response.getStatus(), "超限响应必须为 429 Too Many Requests");
        // 100 req/s → 令牌生成间隔 0.01s → ceil 后建议等待 1 秒
        assertEquals("1", response.getHeader("Retry-After"),
                "Retry-After 必须是 1 个令牌生成间隔的向上取整（秒）");
        assertTrue(response.getContentAsString().contains("43100"),
                "body 必须携带 RATE_LIMIT 错误码 43100，客户端才能识别限流而非服务端错误");
    }

    @Test
    @DisplayName("Redis 执行异常时 fail-open 放行")
    void redisException_shouldFailOpen() throws Exception {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis 故障"));

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result, "Redis 异常时必须降级放行，限流组件故障不能影响创建短链业务");
    }

    @Test
    @DisplayName("Lua 返回结果异常（null）时 fail-open 放行")
    void nullResult_shouldFailOpen() throws Exception {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result, "Lua 返回 null（结果异常）时必须降级放行，不能把异常结果当成拒绝");
    }
}
