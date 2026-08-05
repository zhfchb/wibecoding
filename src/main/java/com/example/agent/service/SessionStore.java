package com.example.agent.service;

import com.example.agent.domain.chat.SessionMessage;

import java.util.List;

/**
 * 会话上下文存储抽象：管理某个 sessionId 下的历史消息。
 * 实现可基于内存（测试）或 Redis（生产）。
 */
public interface SessionStore {

    /**
     * 加载会话历史（按时间正序），最多 maxHistory 条。
     */
    List<SessionMessage> load(String sessionId);

    /**
     * 追加一条消息到会话历史，并刷新 TTL。
     */
    void append(String sessionId, SessionMessage message);

    /**
     * 清空会话历史。
     */
    void clear(String sessionId);
}
