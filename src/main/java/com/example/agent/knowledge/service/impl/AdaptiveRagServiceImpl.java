package com.example.agent.knowledge.service.impl;

import com.example.agent.chat.dto.RagChatResponse;
import com.example.agent.chat.dto.RagSource;
import com.example.agent.integration.ai.AiGateway;
import com.example.agent.knowledge.config.RagProperties;
import com.example.agent.knowledge.service.AdaptiveRagService;
import com.example.agent.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class AdaptiveRagServiceImpl implements AdaptiveRagService {
    private final AiGateway gateway;
    private final RagProperties properties;
    private final ObjectMapper mapper;
    public RagChatResponse chat(String message, String sessionId) {
        Long owner = SecurityUtils.getCurrentUserId();
        if (owner == null) throw new IllegalStateException("Authenticated user required");
        var config = properties.adaptive();
        var result = gateway.post("/internal/v1/rag/adaptive", Map.of("message", message, "ownerId", owner,
            "maxRetries", config != null ? config.maxRetries() : 1,
            "minScore", config != null ? config.minScore() : 0.35));
        List<RagSource> sources = mapper.convertValue(result.path("sources"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        return new RagChatResponse(sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString(),
            result.path("answer").asText(), sources);
    }
}
