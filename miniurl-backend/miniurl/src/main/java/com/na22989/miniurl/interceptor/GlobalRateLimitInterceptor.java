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

import static com.na22989.miniurl.common.RedisKeyConstant.RATE_GLOBAL_KEY_PREFIX;


/**
 * 全局限流拦截器：为创建短链提供系统级总配额（全体用户共用一个令牌桶）。
 * <p>
 * 设计 §9.3 三维限流的第三维——IP 限流挡跳转、用户限流挡单人刷量，本拦截器挡系统整体容量。
 * 关键决策：
 * <ul>
 *   <li>key 固定为 rate:global:create，不分 IP、不分用户，避免单用户占满全局桶</li>
 *   <li>必须挂在 Login 之后：只有认证通过的创建请求才消耗全局令牌，防未登录刷量打满桶误伤正常用户</li>
 *   <li>Redis 异常 fail-open 放行（限流组件故障不影响业务），与 {@link RateLimitInterceptor} 降级策略一致</li>
 * </ul>
 * 复用通用令牌桶脚本 lua/rate_limit.lua（KEYS[1] 参数化），不新增脚本；配额由
 * rate-limit.global.* 配置项控制（重启生效），后续可扩展为动态配置接口。
 */
@Slf4j
@Component
public class GlobalRateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    public GlobalRateLimitInterceptor(StringRedisTemplate stringRedisTemplate,
                                ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    // 配置项

    @Value("${rate-limit.global.enabled:true}")
    private boolean enabled;

    @Value("${rate-limit.global.tokens-per-second:100}")
    private int tokensPerSecond;

    @Value("${rate-limit.global.max-tokens:100}")
    private int maxTokens;

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
            log.info("[全局限流] 限流功能已禁用");
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource("lua/rate_limit.lua");
            byte[] scriptBytes = FileCopyUtils.copyToByteArray(resource.getInputStream());
            String scriptContent = new String(scriptBytes, StandardCharsets.UTF_8);

            rateLimitScript = new DefaultRedisScript<>();
            rateLimitScript.setScriptText(scriptContent);
            // List.class 是原始类型 Class<List>，强转适配泛型擦除
            rateLimitScript.setResultType((Class) List.class);

            log.info("[全局限流] Lua 脚本加载成功 | 速率={}个/秒 | 容量={}",
                    tokensPerSecond, maxTokens);

        } catch (IOException e) {
            log.error("[全局限流] Lua 脚本加载失败，服务启动失败", e);
            throw new IllegalStateException("全局限流 Lua 脚本加载失败", e);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!enabled) {
            return true;
        }

        boolean allowed = checkRateLimit();

        if (allowed) {
            return true;
        } else {
            handleRateLimitExceeded(response);
            return false;
        }
    }

    private boolean checkRateLimit() {
        try {
            String key = RATE_GLOBAL_KEY_PREFIX + "create";

            long now = System.currentTimeMillis();

            // 执行 Lua 脚本
            // KEYS[1] = rate:global:create
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
                log.error("[全局限流] Lua 脚本返回结果异常: {}", result);
                return true;  // 降级：放行
            }

            long allowed = result.get(0);      // 1=放行, 0=拒绝
            long remaining = result.get(1);    // 剩余令牌数

            if (allowed == 1) {
                // 放行（仅在 DEBUG 级别记录，避免日志过多）
                if (log.isDebugEnabled()) {
                    log.debug("[全局限流] 剩余令牌={}", remaining);
                }
                return true;
            } else {
                log.warn("[全局限流] 剩余令牌={}", remaining);
                return false;
            }

        } catch (Exception e) {
            log.error("[全局限流] 执行限流检查异常，降级放行", e);
            return true;  // 降级：放行（避免限流组件故障影响业务）
        }
    }

    private void handleRateLimitExceeded(HttpServletResponse response)
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
