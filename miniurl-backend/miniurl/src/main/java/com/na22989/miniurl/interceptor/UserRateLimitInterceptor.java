package com.na22989.miniurl.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.na22989.miniurl.common.Result;
import com.na22989.miniurl.common.ResultCodeEnum;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static com.na22989.miniurl.common.RedisKeyConstant.RATE_USER_KEY_PREFIX;

/**
 * 基于 Redis ZSET + Lua 滑动窗口的用户级限流拦截器
 * <p>
 * === 功能 ===
 * - 针对 /api/link/create 等写操作进行用户级限流
 * - 以 userId 作为限流维度（从 LoginInterceptor 写入的 request Attribute 获取）
 * - 使用 Lua 脚本实现 ZSET 滑动窗口算法（原子性保证）
 * <p>
 * === 限流策略 ===
 * - 滑动窗口算法：精确控制窗口内请求次数，不允许多余突发
 * - 窗口大小 + 最大请求数由 application.yml 配置
 * <p>
 * === 拒绝策略 ===
 * - HTTP 429 Too Many Requests
 * - 响应头 Retry-After: N（精确到窗口最早请求过期时间）
 * - 直接写 JSON 到 response（拦截器异常不被 @RestControllerAdvice 捕获，
 *   与 LoginInterceptor 保持一致，不走异常抛出路径）
 */
@Slf4j
@Component
public class UserRateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public UserRateLimitInterceptor(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${rate-limit.user.enabled:true}")
    private boolean enabled;

    @Value("${rate-limit.user.window-ms:60000}")
    private int windowMs;

    @Value("${rate-limit.user.max-requests:50}")
    private int maxRequests;

    private DefaultRedisScript<List<Long>> rateLimitScript;



    /**
     * 启动时加载 Lua 脚本
     * <p>
     * 如果加载失败，服务启动失败（符合 Fail-Fast 原则）
     */
    @PostConstruct
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void init() {
        if (!enabled) {
            log.info("[滑动窗口] 滑动窗口功能已禁用");
            return;
        }

        try {
            // 从 classpath 加载 Lua 脚本
            ClassPathResource resource = new ClassPathResource("lua/rate_limit_user.lua");
            byte[] scriptBytes = FileCopyUtils.copyToByteArray(resource.getInputStream());
            String scriptContent = new String(scriptBytes, StandardCharsets.UTF_8);

            // 创建 RedisScript 对象
            rateLimitScript = new DefaultRedisScript<>();
            rateLimitScript.setScriptText(scriptContent);
            // List.class 是原始类型 Class<List>，强转适配泛型擦除
            rateLimitScript.setResultType((Class) List.class);

            log.info("[滑动窗口] Lua 脚本加载成功 | 滑动窗口长度={}毫秒 | 滑动窗口请求上限={}", windowMs, maxRequests);

        } catch (IOException e) {
            log.error("[滑动窗口] Lua 脚本加载失败，服务启动失败", e);
            throw new IllegalStateException("滑动窗口 Lua 脚本加载失败", e);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 1. 检查是否启用限流
        if (!enabled) {
            return true;
        }

        // 2. 获取用户 ID 和 操作类型
        Long userId = (Long) request.getAttribute("userId");
        String action = extractAction(request);

        // 3. 执行限流检查
        long[] resetTimeHolder = new long[1];
        boolean allowed = checkRateLimit(userId, action, resetTimeHolder);

        // 4. 放行或拒绝
        if (allowed) {
            return true;
        } else {
            handleUserRateLimitExceeded(userId, resetTimeHolder[0], response);
            return false;
        }
    }

    /**
     * 执行限流检查（调用 Redis Lua 脚本）
     * @param userId          用户 ID
     * @param action          请求动作
     * @param resetTimeHolder [0] = 窗口重置毫秒时间戳（被拒绝时用于 Retry-After）
     * @return true = 放行，false = 拒绝
     */
    private boolean checkRateLimit(Long userId, String action, long[] resetTimeHolder) {
        try {
            if (action == null || action.isEmpty()) {
                return true;
            }

            // Redis key
            String key = RATE_USER_KEY_PREFIX + userId + ":" + action;

            // 当前毫秒时间戳
            long nowMs = System.currentTimeMillis();

            // 执行 Lua 脚本
            // KEYS[1] = rate:user:{userId}:{action}
            // ARGV[1] = window_ms (窗口大小 (毫秒)
            // ARGV[2] = max_requests (窗口内最大请求数)
            // ARGV[3] = now_ms (当前毫秒时间戳)
            // ARGV[4] = 请求唯一标识（Java: String.valueOf(System.nanoTime())）
            List<Long> result = stringRedisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(windowMs),
                    String.valueOf(maxRequests),
                    String.valueOf(nowMs),
                    String.valueOf(System.nanoTime())
            );

            // 解析结果
            if (result == null || result.size() < 3) {
                log.error("[滑动窗口] Lua 脚本返回结果异常: {}", result);
                return true;  // 降级：放行
            }

            long allowed = result.get(0);      // 1=放行, 0=拒绝
            long remaining = result.get(1);    // 剩余窗口可用次数
            long resetTimeMs = result.get(2);  // 剩余窗口重置时间

            if (allowed == 1) {
                // 放行（仅在 DEBUG 级别记录，避免日志过多）
                if (log.isDebugEnabled()) {
                    log.debug("[滑动窗口] userId={} 放行 | 剩余窗口可用次数={} | 剩余窗口重置时间={}", userId,remaining,
                            resetTimeMs);
                }
                return true;
            } else {
                // 拒绝
                log.warn("[滑动窗口] userId={} 被限流 | 剩余次数={}", userId, remaining);
                resetTimeHolder[0] = resetTimeMs;
                return false;
            }

        } catch (Exception e) {
            log.error("[滑动窗口] 执行限流检查异常，降级放行", e);
            return true;  // 降级：放行（避免晃动组件故障影响业务）
        }
    }

    private String extractAction(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return "unknown";
        }

        // 去掉首尾斜杠后分割
        String path = uri.startsWith("/") ? uri.substring(1) : uri;
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String[] segments = path.split("/");
        return segments.length > 0 ? segments[segments.length - 1] : "unknown";
    }

    /**
     * 处理滑动窗口拒绝
     *
     * @param userId      用户 ID
     * @param resetTimeMs 窗口重置毫秒时间戳（来自 Lua 脚本）
     * @param response    HTTP 响应对象
     */
    private void handleUserRateLimitExceeded(Long userId, long resetTimeMs, HttpServletResponse response)
            throws IOException {
        // 根据最早请求过期时间计算精确等待秒数
        long retryAfterSeconds = Math.max(1, (resetTimeMs - System.currentTimeMillis()) / 1000);

        // 设置响应头
        response.setStatus(429);  // Too Many Requests
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json;charset=UTF-8");

        Result<?> result = Result.fail(ResultCodeEnum.RATE_LIMIT,
                String.format("操作过于频繁，请 %d 秒后重试", retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
