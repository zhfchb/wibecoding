package com.example.agent.service.impl;

import com.example.agent.config.ChatSessionProperties;
import com.example.agent.domain.chat.SessionMessage;
import com.example.agent.service.SessionStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 会话存储：生产环境使用。
 * 通过 chat.session.store-type=redis（默认）启用。
 * key: chat:session:{sessionId}，value: JSON 数组，TTL 自动过期。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "chat.session", name = "store-type", havingValue = "redis", matchIfMissing = true)
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "chat:session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatSessionProperties properties;

    public RedisSessionStore(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             ChatSessionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<SessionMessage> load(String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            if (json == null) {
                return List.of();
            }
            List<SessionMessage> all = objectMapper.readValue(json, new TypeReference<>() {});
            int size = all.size();
            int from = Math.max(0, size - properties.maxHistory());
            return new ArrayList<>(all.subList(from, size));
        } catch (Exception e) {
            log.error("加载会话历史失败 sessionId={}", sessionId, e);
            return List.of();
        }
    }

    @Override
    public void append(String sessionId, SessionMessage message) {
        try {
            List<SessionMessage> all = new ArrayList<>(load(sessionId));
            all.add(message);
            // 超过最大轮数时裁剪旧消息
            int from = Math.max(0, all.size() - properties.maxHistory());
            if (from > 0) {
                all = new ArrayList<>(all.subList(from, all.size()));
            }
            String json = objectMapper.writeValueAsString(all);
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + sessionId,
                    json,
                    Duration.ofSeconds(properties.ttlSeconds()));
        } catch (Exception e) {
            log.error("保存会话历史失败 sessionId={}", sessionId, e);
        }
    }

    @Override
    public void clear(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }
}
