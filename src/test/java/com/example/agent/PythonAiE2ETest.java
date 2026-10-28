package com.example.agent;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.http.MediaType;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real Java/Python HTTP + H2 + LangChain/LangGraph, using a local fake model provider. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="RUN_AI_E2E", matches="true")
class PythonAiE2ETest {
    static final int AI_PORT = freePort();
    static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("ai.service.base-url", () -> "http://127.0.0.1:" + AI_PORT);
        registry.add("ai.service.token", () -> "e2e-internal-token");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:python-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1");
        registry.add("rag.embedding.provider", () -> "openai");
        registry.add("rag.rerank.enabled", () -> true);
        registry.add("rag.rerank.provider", () -> "siliconflow");
    }
    @LocalServerPort int javaPort;
    final ObjectMapper mapper = new ObjectMapper();
    HttpServer provider;
    Process python;
    Path database;

    @BeforeAll void start() throws Exception {
        database = Files.createTempDirectory("langgraph-e2e-").resolve("workflows.sqlite");
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/embeddings", exchange -> {
            var input = mapper.readTree(exchange.getRequestBody()).path("input");
            List<Object> data = new ArrayList<>();
            for (int i = 0; i < input.size(); i++) {
                double[] vector = new double[768]; vector[0] = 1;
                data.add(Map.of("index", i, "object", "embedding", "embedding", vector));
            }
            byte[] bytes = mapper.writeValueAsBytes(Map.of("object", "list", "data", data, "model", "test-embedding", "usage", Map.of("prompt_tokens", 1, "total_tokens", 1)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        provider.createContext("/v1/rerank", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            List<Object> ranked = new ArrayList<>();
            for (int i = 0; i < request.path("documents").size(); i++) ranked.add(Map.of("index", i, "relevance_score", .9));
            byte[] bytes = mapper.writeValueAsBytes(Map.of("results", ranked));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        provider.createContext("/v1/chat/completions", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            String prompt = request.path("messages").toString();
            Object output = "answer [1]";
            if (prompt.contains("candidate_name")) output = Map.of("candidate_name", "Synthetic", "skills", List.of("Java"), "years_exp", 3, "education", "本科");
            else if (prompt.contains("resumeId")) output = Map.of("items", List.of(Map.of("resumeId", 1, "score", 85, "notes", "Java", "hits", List.of("Java"), "gaps", List.of())));
            else if (prompt.contains("referenceAnswer")) output = Map.of("items", Collections.nCopies(6, Map.of("category", "技术", "question", "Java?", "assessPoint", "test", "referenceAnswer", "test", "difficulty", "EASY")));
            else if (prompt.contains("finalRecommend")) output = Map.of("summary", "synthetic summary", "strength", "Java", "risk", "verify", "salarySuggest", "unknown", "finalRecommend", "YES", "reportMd", "# Synthetic report");
            String content = output instanceof String ? (String)output : mapper.writeValueAsString(output);
            byte[] bytes;
            if (request.path("stream").asBoolean()) {
                String event = mapper.writeValueAsString(Map.of("id", "fake", "object", "chat.completion.chunk", "created", 1,
                    "model", "deepseek-chat", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", content), "finish_reason", "stop"))));
                bytes = ("data: " + event + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            } else {
                bytes = mapper.writeValueAsBytes(Map.of("id", "fake", "object", "chat.completion", "created", 1,
                    "model", "deepseek-chat", "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content), "finish_reason", "stop"))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        provider.start();
        startPython();
    }

    void startPython() throws Exception {
        var builder = new ProcessBuilder(System.getenv("AI_TEST_PYTHON"), "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", String.valueOf(AI_PORT));
        builder.directory(Path.of("ai-service").toFile());
        var env = builder.environment();
        env.put("AI_SERVICE_TOKEN", "e2e-internal-token");
        env.put("DEEPSEEK_CLIENT_API_KEY", "fake-provider-key");
        env.put("DEEPSEEK_BASE_URL", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
        env.put("RAG_EMBEDDING_BASE_URL", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
        env.put("RAG_EMBEDDING_API_KEY", "fake-embedding-key");
        env.put("RAG_RERANK_BASE_URL", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
        env.put("RAG_RERANK_API_KEY", "fake-rerank-key");
        env.put("JAVA_SERVICE_URL", "http://127.0.0.1:" + javaPort);
        env.put("AI_CHECKPOINT_DB", database.toString());
        env.put("LANGSMITH_TRACING", "false");
        env.remove("TAVILY_API_KEY");
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("target/python-e2e.log").toFile().getAbsoluteFile()));
        python = builder.start();
        var client = RestClient.create("http://127.0.0.1:" + AI_PORT);
        for (int i = 0; i < 100; i++) {
            if (!python.isAlive()) throw new IllegalStateException("Python failed; see target/python-e2e.log");
            try { client.get().uri("/health").retrieve().toBodilessEntity(); return; }
            catch (Exception e) { Thread.sleep(100); }
        }
        throw new IllegalStateException("Python startup timed out");
    }

    @AfterAll void stop() throws Exception {
        if (python != null) { python.destroy(); python.waitFor(10, TimeUnit.SECONDS); if (python.isAlive()) python.destroyForcibly(); }
        if (provider != null) provider.stop(0);
    }
    RestClient user(String name) {
        var client = RestClient.create("http://127.0.0.1:" + javaPort);
        var auth = client.post().uri("/api/v1/auth/register").body(Map.of("username", name, "password", "test-password-123")).retrieve().body(JsonNode.class);
        return RestClient.builder().baseUrl("http://127.0.0.1:" + javaPort).defaultHeader("Authorization", "Bearer " + auth.path("token").asText()).build();
    }
    JsonNode post(RestClient client, String path, Object body) { return client.post().uri(path).body(body).retrieve().body(JsonNode.class); }

    @Test void recruitmentRagStreamingIsolationAndDurableReview() throws Exception {
        assertThatThrownBy(() -> RestClient.create("http://127.0.0.1:"+javaPort).post().uri("/internal/v1/retrieve")
            .body(Map.of("query", "Java", "ownerId", 1)).retrieve().toBodilessEntity())
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
        var user = user("e2e-owner");
        var other = user("e2e-other");
        var position = post(user, "/api/v1/recruitment/positions", Map.of("title", "Java", "requiredSkills", List.of("Java"), "minYears", 1));
        long id = position.path("id").asLong();
        var parts = new LinkedMultiValueMap<String,Object>();
        parts.add("files", new ByteArrayResource("Java Spring project experience three years".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "synthetic.md"; }
        });
        var upload = user.post().uri("/api/v1/recruitment/positions/"+id+"/resumes").contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(JsonNode.class);
        assertThat(upload.path("successCount").asInt()).isEqualTo(1);
        long resume = upload.path("items").get(0).path("resumeId").asLong();
        var resumes = user.get().uri("/api/v1/recruitment/positions/"+id+"/resumes").retrieve().body(JsonNode.class);
        assertThat(resumes.get(0).path("parseStatus").asText()).isEqualTo("PARSED");
        var matches = post(user, "/api/v1/recruitment/positions/"+id+"/match/run", Map.of());
        assertThat(matches.get(0).path("scoreDetail").path("llmScore").asInt()).isEqualTo(85);
        assertThat(post(user,"/api/v1/recruitment/resumes/"+resume+"/questions/generate", Map.of()).size()).isEqualTo(6);
        assertThat(post(user,"/api/v1/recruitment/resumes/"+resume+"/report/generate", Map.of()).path("summary").asText()).isEqualTo("synthetic summary");
        var answer = post(user, "/api/v1/chat", Map.of("message", "Java", "sessionId", "shared"));
        assertThat(answer.path("sources").size()).isGreaterThan(0);
        var isolated = post(other, "/api/v1/chat", Map.of("message", "Java", "sessionId", "shared"));
        assertThat(isolated.path("sources").size()).isZero();
        var stream = user.post().uri("/api/v1/chat/stream").body(Map.of("message", "Java", "sessionId", "stream")).retrieve().body(String.class);
        assertThat(stream).contains("answer [1]");
        var history = user.get().uri("/api/v1/chat/history/stream").retrieve().body(JsonNode.class);
        assertThat(history.size()).isEqualTo(2);
        assertThat(other.get().uri("/api/v1/chat/history/stream").retrieve().body(JsonNode.class).size()).isZero();
        assertThat(post(user, "/api/v1/rag/adaptive", Map.of("message", "Java")).path("sources").size()).isGreaterThan(0);
        var task = post(user, "/api/v1/agent/multi-agent", Map.of("question", "Java"));
        String taskId = task.path("taskId").asText();
        assertThat(task.path("status").asText()).isEqualTo("AWAITING_REVIEW");
        assertThat(other.get().uri("/api/v1/agent/tasks/pending").retrieve().body(JsonNode.class).size()).isZero();
        assertThatThrownBy(() -> other.get().uri("/api/v1/agent/tasks/"+taskId).retrieve().toBodilessEntity())
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
        python.destroy(); python.waitFor(10, TimeUnit.SECONDS); if (python.isAlive()) python.destroyForcibly().waitFor();
        startPython();
        var revised = post(user, "/api/v1/agent/tasks/"+taskId+"/reject", Map.of("feedback", "more evidence"));
        assertThat(revised.path("rejectCount").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> post(user, "/api/v1/agent/tasks/"+taskId+"/reject", Map.of("feedback", "duplicate", "expectedRejectCount", 0)))
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Conflict.class);
        assertThat(post(user, "/api/v1/agent/tasks/"+taskId+"/approve", Map.of()).path("status").asText()).isEqualTo("COMPLETED");
    }
}
