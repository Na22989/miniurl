package com.na22989.miniurl.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.refresh-expiration}")
    private Long refreshExpiration; // 单位：毫秒

    @Value("${jwt.access-expiration}")
    private Long accessExpiration; // 单位：毫秒


    /**
     * 生成 accessToken（30分钟）
     */
    public String generateAccessToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        return generateToken(userId, claims, accessExpiration);
    }

    /**
     * 生成 refreshToken（7天）
     */
    public String generateRefreshToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "refresh");
        return generateToken(userId, claims, refreshExpiration);
    }

    private String generateToken(Long userId, Map<String, Object> extraClaims, Long expirationMs) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.put("userId", userId);
        return Jwts.builder()
                .claims(claims)
                .subject(userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSignKey())
                .compact();
    }

    public Long extractUserId(String token) {
        return Long.parseLong(extractClaim(token, Claims::getSubject));
    }

    public Date extractIssuedAt(String token) {
        return extractClaim(token, Claims::getIssuedAt);
    }

    public String extractType(String token) {
        return extractClaim(token, claims -> claims.get("type", String.class));
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * 验证 Token 是否有效（签名正确、未过期）且 type 与期望一致
     *
     * @param token        JWT 令牌
     * @param expectedType 期望的 type（"access" / "refresh"）
     * @return 有效且类型匹配返回 true，否则 false
     */
    public Boolean validateToken(String token, String expectedType) {
        try {
            Claims claims = extractAllClaims(token);
            String type = claims.get("type", String.class);
            return type.equals(expectedType);
        } catch (Exception e) {
            return false;
        }
    }


    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }


    private SecretKey getSignKey() {
        // 注意：秘钥长度至少256位（32字符）
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
