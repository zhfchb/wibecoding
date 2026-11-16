package com.example.agent.assistant.service.impl;

import com.example.agent.assistant.service.AgentService;
import com.example.agent.integration.ai.AiGateway;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {
    private final AiGateway gateway;
    public String chat(String message) {
        return gateway.post("/internal/v1/agent", Map.of("message", message)).path("answer").asText();
    }
}
