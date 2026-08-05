package com.example.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥(HS256,至少 32 字符) */
    private String secret;

    /** 过期时间(毫秒),默认 24 小时 */
    private long expirationMs = 86400000L;
}
