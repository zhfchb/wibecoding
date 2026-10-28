package com.example.agent.service.impl;

import com.example.agent.service.ResumeParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "ai.resume.provider", havingValue = "python")
public class PythonResumeParser implements ResumeParser {
    private final RestClient client;

    public PythonResumeParser(
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service.token:}") String token) {
        if (token.isBlank()) {
            throw new IllegalStateException("AI_SERVICE_TOKEN is required for Python resume parsing");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(60000);
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-AI-Service-Token", token).build();
    }

    @Override
    public JsonNode parse(String text) {
        try {
            JsonNode result = client.post().uri("/internal/v1/resumes/parse")
                    .body(Map.of("text", text.substring(0, Math.min(text.length(), 12000))))
                    .retrieve().body(JsonNode.class);
            if (result == null || !result.isObject() || !result.path("skills").isArray()) {
                throw new IllegalStateException("Invalid extraction response");
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Python resume extraction failed; check AI service availability");
        }
    }
}
