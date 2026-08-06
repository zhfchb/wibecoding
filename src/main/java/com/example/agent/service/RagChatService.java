package com.example.agent.service;

import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.entity.ChatHistoryEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface RagChatService {

    /**
     * RAG 对话（支持多轮会话）。
     * @param message 用户消息
     * @param sessionId 会话 ID，为空时生成新会话
     */
    RagChatResponse chat(String message, String sessionId);

    /**
     * 流式 RAG 对话：逐 token 推送，适合前端打字机效果。
     */
    SseEmitter streamChat(String message, String sessionId);

    /**
     * 查询某个会话的对话历史。
     */
    List<ChatHistoryEntity> getHistory(String sessionId);

    /**
     * 列出所有会话（每个会话取最后一条消息），按最后更新时间倒序。
     */
    List<ChatHistoryEntity> listSessions();

    /**
     * 删除指定会话的所有历史消息和上下文。
     * @param sessionId 会话 ID
     */
    void deleteSession(String sessionId);
}
