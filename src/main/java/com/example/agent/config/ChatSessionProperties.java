package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多轮会话配置
 */
@ConfigurationProperties(prefix = "chat.session")
public record ChatSessionProperties(
        String storeType,    // memory / redis
        long ttlSeconds,     // 会话过期时间
        int maxHistory       // 保留的最大历史轮数
) {
    public ChatSessionProperties {
        if (storeType == null || storeType.isBlank()) {
            // 默认 redis，测试环境通过 application-test.yml 覆盖为 memory
            storeType = "redis";
        }
        if (ttlSeconds <= 0) {
            ttlSeconds = 3600;
        }
        if (maxHistory <= 0) {
            maxHistory = 20;
        }
    }
}
