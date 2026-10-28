package com.example.agent.controller;

import com.example.agent.config.OpenApiConfig;
import com.example.agent.config.RagProperties;
import com.example.agent.domain.ChatRequest;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.service.AdaptiveRagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 增强链路控制器（M8）：承载自适应 RAG 等高级检索端点。
 * 普通对话 / 流式对话 / 历史查询仍在 {@link ChatController}。
 */
@Tag(name = "RAG 增强", description = "自适应 RAG（Python LangGraph 状态图：查询重写→检索→评分→重试/生成）")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final AdaptiveRagService adaptiveRagService;
    private final RagProperties ragProperties;

    @Operation(summary = "自适应 RAG 对话",
            description = "基于 Python LangGraph 状态图：查询重写 → 混合检索 → 质量评分 → 重试/生成。"
                    + "检索 top1 分数低于阈值时自动重写查询重试，重试耗尽走兜底回答。"
                    + "通过 rag.adaptive.enabled 控制开关，关闭时返回 501。")
    @PostMapping("/adaptive")
    public ResponseEntity<?> adaptive(@RequestBody ChatRequest request) {
        RagProperties.Adaptive config = ragProperties.adaptive();
        if (config == null || !config.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "自适应 RAG 未启用，请在配置中设置 rag.adaptive.enabled=true"));
        }
        RagChatResponse response = adaptiveRagService.chat(request.getMessage(), request.getSessionId());
        return ResponseEntity.ok(response);
    }
}
