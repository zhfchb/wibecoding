package com.example.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;
import java.util.function.Consumer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Internal HTTP boundary. User authorization is enforced before reaching this client. */
@Component
public class AiGateway {
    private final RestClient client;

    public AiGateway(@Value("${ai.service.base-url:http://localhost:8000}") String baseUrl,
                     @Value("${ai.service.token:}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(90000);
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-AI-Service-Token", token).build();
    }

    public JsonNode post(String path, Object body) {
        try {
            JsonNode result = client.post().uri(path).body(body).retrieve().body(JsonNode.class);
            if (result == null) throw new IllegalStateException();
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("AI service request failed: " + path);
        }
    }

    public String structured(String operation, String prompt) {
        JsonNode result = post("/internal/v1/generate", Map.of("operation", operation, "prompt", prompt));
        return (result.has("items") ? result.get("items") : result).toString();
    }

    public String chat(String prompt) {
        return post("/internal/v1/chat", Map.of("message", prompt)).path("answer").asText();
    }

    public void stream(String path, Object body, Consumer<JsonNode> consumer) {
        try {
            client.post().uri(path).body(body).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException();
                var reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
                String line;
                var mapper = new ObjectMapper();
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) consumer.accept(mapper.readTree(line));
                }
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException("AI stream failed");
        }
    }
}
