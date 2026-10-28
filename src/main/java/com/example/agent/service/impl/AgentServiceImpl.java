package com.example.agent.service.impl;
import com.example.agent.service.AiGateway;
import com.example.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {
    private final AiGateway gateway;
    public String chat(String message) {
        return gateway.post("/internal/v1/agent", Map.of("message", message)).path("answer").asText();
    }
}
