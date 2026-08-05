package com.example.agent.controller;

import com.example.agent.config.OpenApiConfig;
import com.example.agent.domain.ChatRequest;
import com.example.agent.entity.ChatHistoryEntity;
import com.example.agent.service.ChatService;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.service.RagChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Tag(name = "RAG 对话", description = "简单对话 / RAG 检索增强对话 / SSE 流式对话 / 对话历史")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final RagChatService ragChatService;

    @Operation(summary = "简单对话(无 RAG)", description = "直接调 LLM,不检索知识库")
    @PostMapping("/simple")
    public String simpleChat(@RequestBody ChatRequest request) {
        return chatService.chat(request.getMessage());
    }

    @Operation(summary = "RAG 对话", description = "检索知识库 + LLM 生成,返回答案和来源")
    @PostMapping
    public RagChatResponse ragChat(@RequestBody ChatRequest request) {
        return ragChatService.chat(request.getMessage(), request.getSessionId());
    }

    /**
     * 流式 RAG 对话：返回 SSE 流，前端逐 token 渲染打字机效果。
     */
    @Operation(summary = "SSE 流式 RAG 对话", description = "流式返回,Content-Type: text/event-stream,前端逐 token 渲染打字机效果")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        return ragChatService.streamChat(request.getMessage(), request.getSessionId());
    }

    /**
     * 查询某个会话的对话历史。
     */
    @Operation(summary = "查询对话历史", description = "根据 sessionId 查询该会话的所有历史消息")
    @GetMapping("/history/{sessionId}")
    public List<ChatHistoryEntity> history(@PathVariable String sessionId) {
        return ragChatService.getHistory(sessionId);
    }
}
