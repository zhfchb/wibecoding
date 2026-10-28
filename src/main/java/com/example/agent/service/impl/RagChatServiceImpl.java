package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.*;
import com.example.agent.entity.ChatHistoryEntity;
import com.example.agent.mapper.ChatHistoryMapper;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatServiceImpl implements RagChatService {
    private final AiGateway gateway;
    private final SessionStore sessionStore;
    private final ChatHistoryMapper chatHistoryMapper;
    private final RagProperties properties;
    private final org.springframework.core.task.TaskExecutor aiTaskExecutor;
    private final ObjectMapper mapper;

    private Long owner() {
        Long id = SecurityUtils.getCurrentUserId();
        if (id == null) throw new IllegalStateException("Authenticated user required");
        return id;
    }

    private Map<String, Object> request(String message, String session, Long owner) {
        if (!StringUtils.hasText(message)) throw new IllegalArgumentException("message required");
        var all = getHistory(session);
        var history = all.subList(Math.max(0, all.size() - 20), all.size()).stream()
                .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent())).toList();
        var ws = properties.webSearch();
        return Map.of("message", message, "ownerId", owner, "history", history,
                "webFallback", ws != null && ws.enabled(),
                "fallbackMinScore", ws != null ? ws.fallbackMinScore() : 0.35);
    }

    @Override
    public RagChatResponse chat(String message, String sessionId) {
        Long owner = owner();
        String session = resolveSession(sessionId);
        var result = gateway.post("/internal/v1/rag", request(message, session, owner));
        String answer = result.path("answer").asText();
        var sources = mapper.convertValue(result.path("sources"), new com.fasterxml.jackson.core.type.TypeReference<List<RagSource>>() {});
        saveHistory(session, owner, message, answer);
        return new RagChatResponse(session, answer, sources);
    }

    @Override
    public SseEmitter streamChat(String message, String sessionId) {
        Long owner = owner();
        String session = resolveSession(sessionId);
        var body = request(message, session, owner);
        var emitter = new SseEmitter(120000L);
        var stopped = new AtomicBoolean();
        emitter.onCompletion(() -> stopped.set(true));
        emitter.onTimeout(() -> stopped.set(true));
        emitter.onError(error -> stopped.set(true));
        aiTaskExecutor.execute(() -> {
            var answer = new StringBuilder();
            var done = new AtomicBoolean();
            try {
                gateway.stream("/internal/v1/rag/stream", body, event -> {
                    if (stopped.get()) throw new IllegalStateException("Stream closed");
                    switch (event.path("type").asText()) {
                        case "token" -> {
                            String token = event.path("text").asText();
                            answer.append(token);
                            try { emitter.send(SseEmitter.event().data(token)); }
                            catch (Exception e) { throw new IllegalStateException("Client disconnected"); }
                        }
                        case "done" -> done.set(true);
                        case "error" -> throw new IllegalStateException("AI generation failed");
                    }
                });
                if (!done.get()) throw new IllegalStateException("Incomplete AI stream");
                if (!stopped.get()) {
                    saveHistory(session, owner, message, answer.toString());
                    emitter.complete();
                }
            } catch (Exception e) { emitter.completeWithError(e); }
        });
        return emitter;
    }

    @Override
    public List<ChatHistoryEntity> getHistory(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            // 无用户上下文时不返回任何历史，防止数据越权
            return List.of();
        }
        return chatHistoryMapper.selectList(
                new LambdaQueryWrapper<ChatHistoryEntity>()
                        .eq(ChatHistoryEntity::getUserId, userId)
                        .eq(ChatHistoryEntity::getSessionId, sessionId)
                        .orderByAsc(ChatHistoryEntity::getId));
    }

    @Override
    public List<ChatHistoryEntity> listSessions() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return List.of();
        }
        return chatHistoryMapper.selectLatestOfEachSession(userId);
    }

    @Override
    public void deleteSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        // 1. 删除 MySQL 持久化历史（仅自己的会话）
        chatHistoryMapper.deleteBySessionId(sessionId, userId);
        // 2. 清除会话上下文缓存（Redis/内存）
        sessionStore.clear(userId + ":" + sessionId);
        log.info("会话已删除: sessionId={}, userId={}", sessionId, userId);
    }

    private String resolveSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();
    }

    private void saveHistory(String session, Long owner, String message, String answer) {
        sessionStore.append(owner + ":" + session, SessionMessage.user(message));
        sessionStore.append(owner + ":" + session, SessionMessage.assistant(answer));
        persistHistory(session, owner, "user", message);
        persistHistory(session, owner, "assistant", answer);
    }

    private void persistHistory(String session, Long owner, String role, String content) {
        ChatHistoryEntity entity = new ChatHistoryEntity();
        entity.setSessionId(session);
        entity.setUserId(owner);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(LocalDateTime.now());
        chatHistoryMapper.insert(entity);
    }
}
