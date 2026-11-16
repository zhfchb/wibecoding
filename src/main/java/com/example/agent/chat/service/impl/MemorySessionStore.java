package com.example.agent.chat.service.impl;

import com.example.agent.chat.config.ChatSessionProperties;
import com.example.agent.chat.dto.SessionMessage;
import com.example.agent.chat.service.SessionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存会话存储：测试环境使用，不依赖 Redis。
 * 通过 chat.session.store-type=memory 启用。
 */
@Component
@ConditionalOnProperty(prefix = "chat.session", name = "store-type", havingValue = "memory")
public class MemorySessionStore implements SessionStore {

    private final Map<String, List<SessionMessage>> store = new ConcurrentHashMap<>();
    private final ChatSessionProperties properties;

    public MemorySessionStore(ChatSessionProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SessionMessage> load(String sessionId) {
        List<SessionMessage> messages = store.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        int size = messages.size();
        int from = Math.max(0, size - properties.maxHistory());
        return new ArrayList<>(messages.subList(from, size));
    }

    @Override
    public void append(String sessionId, SessionMessage message) {
        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
    }

    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }
}
