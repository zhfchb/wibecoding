package com.example.agent.controller;

import com.example.agent.domain.ChatRequest;
import com.example.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * Agent 对话：LLM 自动决策调用工具（时间查询 / 计算器），多轮工具调用后给出答案。
     */
    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest request) {
        return agentService.chat(request.getMessage());
    }
}
