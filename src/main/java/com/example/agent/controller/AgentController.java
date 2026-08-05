package com.example.agent.controller;

import com.example.agent.config.OpenApiConfig;
import com.example.agent.domain.ChatRequest;
import com.example.agent.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Agent 工具调用", description = "LLM 自动决策调用工具(时间查询 / 计算器)")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * Agent 对话：LLM 自动决策调用工具（时间查询 / 计算器），多轮工具调用后给出答案。
     */
    @Operation(summary = "Agent 对话", description = "LLM 自动决策调用工具(时间查询 / 计算器),多轮工具调用后给出答案")
    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest request) {
        return agentService.chat(request.getMessage());
    }
}
