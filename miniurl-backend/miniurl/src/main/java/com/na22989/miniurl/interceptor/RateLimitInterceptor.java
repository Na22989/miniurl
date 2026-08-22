package com.na22989.miniurl.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.na22989.miniurl.common.Result;
import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.util.NetUtil;
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

import static com.na22989.miniurl.common.RedisKeyConstant.RATE_IP_KEY_PREFIX;

/**
 * 基于 Redis + Lua 令牌桶的限流拦截器
 * <p>
 * === 功能 ===
 * - 针对 /s/** 路径（短链接重定向）进行限流
 * - 以客户端真实 IP 作为限流维度
 * - 使用 Lua 脚本实现令牌桶算法（原子性保证）
 * <p>
 * === 限流策略 ===
 * - 令牌桶算法：支持突发流量，平滑限流
 * - 速率：由 application.yml 配置
 * - 容量：由 application.yml 配置
 * <p>
 * === 拒绝策略 ===
 * - HTTP 429 Too Many Requests
 * - 响应头 Retry-After: N（建议等待秒数）
 * - 直接写 JSON 到 response（拦截器异常不被 @RestControllerAdvice 捕获，
 *   与 LoginInterceptor 保持一致，不走异常抛出路径）
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate,
                                ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    // 配置项（从 application.yml 读取）

    @Value("${rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${rate-limit.tokens-per-second:10}")
    private int tokensPerSecond;

    @Value("${rate-limit.max-tokens:100}")
    private int maxTokens;

    @Value("${rate-limit.proxies-to-trust:0}")
    private int proxiesToTrust;

    // Lua 脚本

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
            log.info("[限流] 限流功能已禁用");
            return;
        }

        try {
            // 从 classpath 加载 Lua 脚本
            ClassPathResource resource = new ClassPathResource("lua/rate_limit.lua");
            byte[] scriptBytes = FileCopyUtils.copyToByteArray(resource.getInputStream());
            String scriptContent = new String(scriptBytes, StandardCharsets.UTF_8);

            // 创建 RedisScript 对象
            rateLimitScript = new DefaultRedisScript<>();
            rateLimitScript.setScriptText(scriptContent);
            // List.class 是原始类型 Class<List>，强转适配泛型擦除
            rateLimitScript.setResultType((Class) List.class);

            log.info("[限流] Lua 脚本加载成功 | 速率={}个/秒 | 容量={} | 可信代理层级={}",
                    tokensPerSecond, maxTokens, proxiesToTrust);

        } catch (IOException e) {
            log.error("[限流] Lua 脚本加载失败，服务启动失败", e);
            throw new IllegalStateException("限流 Lua 脚本加载失败", e);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 1. 检查是否启用限流
        if (!enabled) {
            return true;
        }

        // 2. 获取客户端真实 IP
        String clientIp = NetUtil.getIpAddress(request, proxiesToTrust);

        // 3. 执行限流检查
        boolean allowed = checkRateLimit(clientIp);

        // 4. 放行或拒绝
        if (allowed) {
            return true;
        } else {
            handleRateLimitExceeded(clientIp, response);
            return false;
        }
    }

    /**
     * 执行限流检查（调用 Redis Lua 脚本）
     *
     * @param clientIp 客户端 IP
     * @return true = 放行，false = 拒绝
     */
    private boolean checkRateLimit(String clientIp) {
        try {
            // Redis key
            String key = RATE_IP_KEY_PREFIX + clientIp;

            // 当前毫秒时间戳
            long now = System.currentTimeMillis();

            // 执行 Lua 脚本
            // KEYS[1] = rate:ip:{ip}
            // ARGV[1] = rate (令牌生成速率)
            // ARGV[2] = capacity (桶容量)
            // ARGV[3] = now (当前毫秒时间戳)
            // ARGV[4] = requested (本次请求消耗令牌数，固定为 1)
            List<Long> result = stringRedisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(tokensPerSecond),
                    String.valueOf(maxTokens),
                    String.valueOf(now),
                    "1"  // 每次请求消耗 1 个令牌
            );

            // 解析结果
            if (result == null || result.size() < 2) {
                log.error("[限流] Lua 脚本返回结果异常: {}", result);
                return true;  // 降级：放行
            }

            long allowed = result.get(0);      // 1=放行, 0=拒绝
            long remaining = result.get(1);    // 剩余令牌数

            if (allowed == 1) {
                // 放行（仅在 DEBUG 级别记录，避免日志过多）
                if (log.isDebugEnabled()) {
                    log.debug("[限流] IP={} 放行 | 剩余令牌={}", clientIp, remaining);
                }
                return true;
            } else {
                // 拒绝
                log.warn("[限流] IP={} 被限流 | 剩余令牌={}", clientIp, remaining);
                return false;
            }

        } catch (Exception e) {
            log.error("[限流] 执行限流检查异常，降级放行", e);
            return true;  // 降级：放行（避免限流组件故障影响业务）
        }
    }

    /**
     * 处理限流拒绝
     *
     * @param clientIp 客户端 IP
     * @param response HTTP 响应对象
     */
    private void handleRateLimitExceeded(String clientIp, HttpServletResponse response)
            throws IOException {
        // 计算建议等待时间（秒）
        int retryAfterSeconds = (int) Math.ceil(1.0 / tokensPerSecond);

        // 设置响应头
        response.setStatus(429);  // Too Many Requests
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json;charset=UTF-8");

        Result<?> result = Result.fail(ResultCodeEnum.RATE_LIMIT,
                String.format("访问过于频繁，请 %d 秒后重试", retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
