package com.example.agent.service.impl;
import com.example.agent.config.RagProperties;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.*;
import com.example.agent.workflow.multiagent.MultiAgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
@RequiredArgsConstructor
public class MultiAgentServiceImpl implements MultiAgentService {
    private final AiGateway gateway;
    private final ObjectMapper mapper;
    private final RagProperties properties;
    private Long owner() {
        Long id = SecurityUtils.getCurrentUserId();
        if (id == null) throw new IllegalStateException("Authenticated user required");
        return id;
    }
    public MultiAgentTask submit(String question) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question required");
        var config = properties.multiAgent();
        return mapper.convertValue(gateway.post("/internal/v1/tasks/submit", Map.of("question", question,
            "ownerId", owner(), "maxRejects", config != null ? config.maxRejects() : 2)), MultiAgentTask.class);
    }
    public MultiAgentTask getTask(String taskId) {
        return mapper.convertValue(gateway.post("/internal/v1/tasks/get", Map.of("taskId", taskId, "ownerId", owner())), MultiAgentTask.class);
    }
    public List<MultiAgentTask> listPendingTasks() {
        return mapper.convertValue(gateway.post("/internal/v1/tasks/pending", Map.of("ownerId", owner())),
            new com.fasterxml.jackson.core.type.TypeReference<List<MultiAgentTask>>() {});
    }
    public MultiAgentTask approve(String taskId) { return approve(taskId, null); }
    public MultiAgentTask reject(String taskId, String feedback) { return reject(taskId, feedback, null); }
    public MultiAgentTask approve(String taskId, Integer revision) { return review(taskId, true, "", revision); }
    public MultiAgentTask reject(String taskId, String feedback, Integer revision) { return review(taskId, false, feedback == null ? "" : feedback, revision); }
    private MultiAgentTask review(String taskId, boolean approved, String feedback, Integer revision) {
        var current = getTask(taskId);
        return mapper.convertValue(gateway.post("/internal/v1/tasks/review", Map.of("taskId", taskId, "ownerId", owner(),
            "approved", approved, "feedback", feedback, "expectedRejectCount", revision != null ? revision : current.rejectCount())), MultiAgentTask.class);
    }
}
