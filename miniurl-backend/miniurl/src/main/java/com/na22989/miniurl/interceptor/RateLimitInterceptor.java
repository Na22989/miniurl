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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
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

    // Lua 脚本：RedisLuaScripts @Bean 统一加载。字段名与 Bean 名一致，让 Spring 在
    // 两个 RedisScript<List<Long>> 候选间按名消歧（IP 与用户脚本类型相同，无法仅按类型区分）
    private final RedisScript<List<Long>> rateLimitScript;

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate,
                                ObjectMapper objectMapper,
                                RedisScript<List<Long>> rateLimitScript) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.rateLimitScript = rateLimitScript;
    }

    // 配置项

    @Value("${rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${rate-limit.tokens-per-second:10}")
    private int tokensPerSecond;

    @Value("${rate-limit.max-tokens:100}")
    private int maxTokens;

    @Value("${rate-limit.proxies-to-trust:0}")
    private int proxiesToTrust;

    /**
     * 启动时输出限流配置状态。脚本本身由 RedisLuaScripts @Bean 统一加载（fail-fast），
     * 此处仅保留原有启动诊断日志，禁用与否都让运维一眼可见。
     */
    @PostConstruct
    public void logStartupState() {
        if (!enabled) {
            log.info("[限流] 限流功能已禁用");
            return;
        }
        log.info("[限流] Lua 脚本加载成功 | 速率={}个/秒 | 容量={} | 可信代理层级={}",
                tokensPerSecond, maxTokens, proxiesToTrust);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!enabled) {
            return true;
        }

        String clientIp = NetUtil.getIpAddress(request, proxiesToTrust);

        boolean allowed = checkRateLimit(clientIp);

        if (allowed) {
            return true;
        } else {
            handleRateLimitExceeded(clientIp, response);
            return false;
        }
    }

    private boolean checkRateLimit(String clientIp) {
        try {
            String key = RATE_IP_KEY_PREFIX + clientIp;

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
                log.warn("[限流] IP={} 被限流 | 剩余令牌={}", clientIp, remaining);
                return false;
            }

        } catch (Exception e) {
            log.error("[限流] 执行限流检查异常，降级放行", e);
            return true;  // 降级：放行（避免限流组件故障影响业务）
        }
    }

    private void handleRateLimitExceeded(String clientIp, HttpServletResponse response)
            throws IOException {
        // 一个令牌的生成间隔（秒），作为建议等待时间
        int retryAfterSeconds = (int) Math.ceil(1.0 / tokensPerSecond);

        response.setStatus(429);  // Too Many Requests
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json;charset=UTF-8");

        Result<?> result = Result.fail(ResultCodeEnum.RATE_LIMIT,
                String.format("访问过于频繁，请 %d 秒后重试", retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
