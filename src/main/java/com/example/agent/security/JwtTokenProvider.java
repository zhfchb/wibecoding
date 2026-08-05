package com.example.agent.security;

import com.example.agent.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与校验
 * 用 HS256 对称签名,密钥从配置读取
 */
@Component
public class JwtTokenProvider {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 生成 JWT,claim 包含 userId / username / role */
    public String generate(Long userId, String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.getExpirationMs());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** 解析并校验 token,失败抛 JwtException */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从 token 提取 username */
    public String getUsername(String token) {
        return parse(token).get("username", String.class);
    }

    /** 从 token 提取 role */
    public String getRole(String token) {
        return parse(token).get("role", String.class);
    }

    /** 从 token 提取 userId */
    public Long getUserId(String token) {
        return Long.parseLong(parse(token).getSubject());
    }
}
