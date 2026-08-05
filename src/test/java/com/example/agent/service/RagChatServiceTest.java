package com.example.agent.service;

import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.entity.ChatHistoryEntity;
import com.example.agent.mapper.ChatHistoryMapper;
import com.example.agent.rag.VectorMatch;
import com.example.agent.rag.VectorStore;
import com.example.agent.service.impl.RagChatServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    @Test
    void returnsFallbackWhenNoKnowledgeFound() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        SessionStore sessionStore = mock(SessionStore.class);
        ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
        RagChatServiceImpl service = new RagChatServiceImpl(
                chatModel, streamingModel, embeddingModel, vectorStore, properties(),
                sessionStore, chatHistoryMapper);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble())).thenReturn(List.of());
        when(sessionStore.load(anyString())).thenReturn(List.of());

        RagChatResponse response = service.chat("未收录的问题", null);

        assertThat(response.answer()).contains("没有检索到");
        assertThat(response.sources()).isEmpty();
        assertThat(response.sessionId()).isNotBlank();
        verify(chatModel, never()).chat(any(ChatRequest.class));
    }

    @Test
    void answersWithRetrievedSources() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        SessionStore sessionStore = mock(SessionStore.class);
        ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
        RagChatServiceImpl service = new RagChatServiceImpl(
                chatModel, streamingModel, embeddingModel, vectorStore, properties(),
                sessionStore, chatHistoryMapper);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new VectorMatch("id", "doc", "Spring Boot 配置", 0.91)));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("答案是 Spring Boot 配置。")).build());
        when(sessionStore.load(anyString())).thenReturn(List.of());

        RagChatResponse response = service.chat("Spring Boot 怎么配置？", "session-1");

        assertThat(response.answer()).isEqualTo("答案是 Spring Boot 配置。");
        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).score()).isEqualTo(0.91);
        verify(chatModel).chat(any(ChatRequest.class));
        // 验证会话上下文保存（user + assistant 两次）
        verify(sessionStore, times(2)).append(eq("session-1"), any());
        // 验证对话历史持久化（user + assistant 两条）
        verify(chatHistoryMapper, times(2)).insert(any(ChatHistoryEntity.class));
    }

    private RagProperties properties() {
        return new RagProperties(
                new RagProperties.Chunking(50, 10),
                new RagProperties.Retrieval(3, 0),
                new RagProperties.VectorStore("memory", "http://localhost:6333", "knowledge"),
                new RagProperties.Embedding("hash", 768, "text-embedding-3-small", "http://localhost", ""),
                new RagProperties.Upload(1024, List.of("txt", "md", "markdown")));
    }
}
