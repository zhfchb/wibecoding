package com.example.agent.service;

import com.example.agent.service.impl.PythonResumeParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class PythonResumeParserTest {
    @Test
    void sendsTokenAndReadsProfile() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/resumes/parse", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("X-AI-Service-Token");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = "{\"skills\":[\"Java\"],\"years_exp\":3}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders("test-token".equals(token) && request.contains("Java") ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var parser = new PythonResumeParser("http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
            assertThat(parser.parse("Java 三年").path("years_exp").asInt()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> new PythonResumeParser("http://localhost:8000", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void redactsUpstreamFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/resumes/parse", exchange -> {
            byte[] body = "private resume".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var parser = new PythonResumeParser("http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
            assertThatThrownBy(() -> parser.parse("Java"))
                    .isInstanceOf(com.example.agent.exception.AiServiceException.class).hasMessageNotContaining("private");
        } finally {
            server.stop(0);
        }
    }
}
