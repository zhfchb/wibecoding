package com.example.agent.controller;

import com.example.agent.domain.ChatRequest;
import com.example.agent.service.ChatService;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.service.RagChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final RagChatService ragChatService;

    @PostMapping("/simple")
    public String simpleChat(@RequestBody ChatRequest request) {
        return chatService.chat(request.getMessage());
    }

    @PostMapping
    public RagChatResponse ragChat(@RequestBody ChatRequest request) {
        return ragChatService.chat(request.getMessage());
    }

    /**
     * 流式 RAG 对话：返回 SSE 流，前端逐 token 渲染打字机效果。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        return ragChatService.streamChat(request.getMessage());
    }
}
