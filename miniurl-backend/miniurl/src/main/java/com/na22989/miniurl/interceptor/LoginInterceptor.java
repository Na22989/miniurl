package com.na22989.miniurl.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.na22989.miniurl.common.Result;
import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

import static com.na22989.miniurl.common.RedisKeyConstant.BLACKLIST_KEY_PREFIX;

@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // OPTIONS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response, ResultCodeEnum.UNAUTHORIZED, "token不存在");
            return false;
        }

        String token = authHeader.substring(7);
        if (token.isBlank() || !jwtUtil.validateToken(token, "access")) {
            writeUnauthorized(response, ResultCodeEnum.UNAUTHORIZED, "token无效或已过期");
            return false;
        }

        Long userId = jwtUtil.extractUserId(token);

        // 黑名单校验：若 token 签发时间早于拉黑时间，说明登录后已被主动失效
        String blackListTimeStr = stringRedisTemplate.opsForValue().get(BLACKLIST_KEY_PREFIX + userId);
        if (blackListTimeStr != null) {
            long blackListTime = Long.parseLong(blackListTimeStr);
            if (blackListTime > jwtUtil.extractIssuedAt(token).getTime()) {
                writeUnauthorized(response, ResultCodeEnum.TOKEN_REVOKED, "token已被禁用");
                return false;
            }
        }

        request.setAttribute("userId", userId);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, ResultCodeEnum code, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Result<?> result = Result.fail(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
