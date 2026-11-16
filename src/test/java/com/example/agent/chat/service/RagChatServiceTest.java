package com.example.agent.chat.service;

import com.example.agent.chat.entity.ChatHistoryEntity;
import com.example.agent.chat.mapper.ChatHistoryMapper;
import com.example.agent.chat.service.impl.RagChatServiceImpl;
import com.example.agent.integration.ai.AiGateway;
import com.example.agent.knowledge.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagChatServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiGateway gateway = mock(AiGateway.class);
    private final ChatHistoryMapper history = mock(ChatHistoryMapper.class);
    private final SessionStore sessions = mock(SessionStore.class);
    private final RagProperties properties = mock(RagProperties.class);
    @BeforeEach void login() {
        var auth = new UsernamePasswordAuthenticationToken("user", "", List.of());
        auth.setDetails(Map.of("userId", 7L));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(history.selectList(any())).thenReturn(List.of());
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    private RagChatServiceImpl service() {
        return new RagChatServiceImpl(gateway, sessions, history, properties,
            task -> { SecurityContextHolder.clearContext(); task.run(); }, mapper);
    }
    @Test void forwardsOwnerAndPreservesSources() throws Exception {
        when(gateway.post(eq("/internal/v1/rag"), any())).thenReturn(mapper.readTree(
            "{\"answer\":\"answer\",\"sources\":[{\"text\":\"Java\",\"fileName\":\"a.md\",\"chunkIndex\":0,\"score\":0.9}]}"));
        var response = service().chat("Java?", "session");
        assertThat(response.sources()).hasSize(1);
        var saved = ArgumentCaptor.forClass(ChatHistoryEntity.class);
        verify(history, times(2)).insert(saved.capture());
        assertThat(mapper.readTree(saved.getAllValues().get(1).getSourcesJson()).get(0).path("fileName").asText()).isEqualTo("a.md");
        var body = ArgumentCaptor.forClass(Object.class);
        verify(gateway).post(eq("/internal/v1/rag"), body.capture());
        assertThat(((Map<?, ?>)body.getValue()).get("ownerId")).isEqualTo(7L);
        verify(sessions, times(2)).append(eq("7:session"), any());
    }
    @Test void streamingKeepsOwnerOutsideSecurityThread() throws Exception {
        doAnswer(invocation -> {
            Consumer<JsonNode> consumer = invocation.getArgument(2);
            consumer.accept(mapper.readTree("{\"type\":\"sources\",\"sources\":[{\"text\":\"Java\",\"fileName\":\"a.md\",\"chunkIndex\":0,\"score\":0.9}]}"));
            consumer.accept(mapper.readTree("{\"type\":\"token\",\"text\":\"hello\"}"));
            consumer.accept(mapper.readTree("{\"type\":\"done\"}"));
            return null;
        }).when(gateway).stream(eq("/internal/v1/rag/stream"), any(), any());
        service().streamChat("question", "session");
        var rows = ArgumentCaptor.forClass(ChatHistoryEntity.class);
        verify(history, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues()).allMatch(row -> row.getUserId().equals(7L));
        assertThat(mapper.readTree(rows.getAllValues().get(1).getSourcesJson()).get(0).path("fileName").asText()).isEqualTo("a.md");
    }
    @Test void incompleteStreamDoesNotPersistAnswer() throws Exception {
        service().streamChat("question", "session");
        verify(history, never()).insert(any(ChatHistoryEntity.class));
    }
    @Test void rejectsUnauthenticatedGeneration() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service().chat("question", "session")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(gateway);
    }
}
