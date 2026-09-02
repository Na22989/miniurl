package com.na22989.miniurl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * Redis Lua 脚本集中注册。
 * <p>
 * 统一用 @Bean 加载脚本（ResourceScriptSource 惰性读取，资源缺失时 context 启动 fail-fast），
 * 调用方 final 构造注入 {@link RedisScript} 后直接 execute。此前各限流拦截器用
 * FileCopyUtils 手工读字节内联加载，同一种资源两种风格；集中注册后脚本与加载方式同源。
 */
@Configuration
public class RedisLuaScripts {

    /**
     * 原子化锁释放脚本：check-then-delete TOCTOU 防护。
     * KEYS[1]: 锁 key；ARGV[1]: 预期的锁值（owner token）。
     * 返回值：1=删除成功，0=值不匹配未删除。
     */
    @Bean
    public RedisScript<Long> releaseLockScript() {
        return loadScript("lua/release_lock.lua", Long.class);
    }

    /**
     * 令牌桶限流脚本（IP 限流与全局配额共用，KEYS[1] 参数化）。
     * 返回 List&lt;Long&gt;：{allowed, remaining}，KEYS/ARGV 约定见各限流拦截器 execute 处注释。
     */
    @Bean
    public RedisScript<List<Long>> rateLimitScript() {
        return loadScript("lua/rate_limit.lua", List.class);
    }

    /**
     * 用户级滑动窗口限流脚本。
     * 返回 List&lt;Long&gt;：{allowed, remaining, resetTimeMs}，KEYS/ARGV 约定见
     * UserRateLimitInterceptor execute 处注释。
     */
    @Bean
    public RedisScript<List<Long>> rateLimitUserScript() {
        return loadScript("lua/rate_limit_user.lua", List.class);
    }

    /**
     * 从 classpath 加载 Lua 脚本为 RedisScript，统一 resultType。
     *
     * @param path       脚本资源路径（如 lua/release_lock.lua）
     * @param resultType 脚本返回类型（Long 或 List，List 为 raw 类型需强转）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> RedisScript<T> loadScript(String path, Class<?> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType((Class) resultType);
        return script;
    }
}
