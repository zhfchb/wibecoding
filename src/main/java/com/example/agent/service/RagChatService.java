package com.example.agent.service;

import com.example.agent.domain.chat.RagChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface RagChatService {

    RagChatResponse chat(String message);

    /**
     * 流式 RAG 对话：逐 token 推送，适合前端打字机效果。
     */
    SseEmitter streamChat(String message);
}
