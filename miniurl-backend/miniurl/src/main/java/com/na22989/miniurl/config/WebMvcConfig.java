package com.na22989.miniurl.config;

import com.na22989.miniurl.interceptor.LoginInterceptor;
import com.na22989.miniurl.interceptor.RateLimitInterceptor;
import com.na22989.miniurl.interceptor.UserRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置
 *
 * @author wangtianjian
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final UserRateLimitInterceptor userRateLimitInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 放行哪些域名（必须用 patterns，否则 * 会和 allowCredentials 冲突）
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 限流拦截器（优先级最高，拦截 /s/** 短链接访问）
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/s/**")
                .order(1);  // 优先级 1（数字越小优先级越高）

        // 登录拦截器（拦截 /api/** API 访问）
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/user/register",
                        "/api/user/login",
                        "/api/user/refresh",
                        // Swagger / OpenAPI 文档路径
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**"
                )
                .order(2);  // 优先级 2

        // 用户级滑动窗口限流拦截器（必须在 LoginInterceptor 之后，依赖 userId Attribute）
        registry.addInterceptor(userRateLimitInterceptor)
                .addPathPatterns("/api/link/create")
                .order(3);  // 优先级 3
    }
}
