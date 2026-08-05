package com.example.agent.domain.chat;

/**
 * 会话消息（role + content），用于多轮上下文存储
 */
public record SessionMessage(String role, String content) {
    public static SessionMessage user(String content) {
        return new SessionMessage("user", content);
    }

    public static SessionMessage assistant(String content) {
        return new SessionMessage("assistant", content);
    }

    public boolean isUser() {
        return "user".equals(role);
    }

    public boolean isAssistant() {
        return "assistant".equals(role);
    }
}
