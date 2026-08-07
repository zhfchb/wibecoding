package com.example.agent.service;

import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.entity.ChatHistoryEntity;
import com.example.agent.mapper.ChatHistoryMapper;
import com.example.agent.rag.HashingSparseModel;
import com.example.agent.rag.NoopRerankModel;
import com.example.agent.rag.RerankModel;
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
import static org.mockito.ArgumentMatchers.anyList;
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
        AgentService agentService = mock(AgentService.class);
        RagChatServiceImpl service = new RagChatServiceImpl(
                chatModel, streamingModel, embeddingModel, vectorStore, properties(),
                sessionStore, chatHistoryMapper, new HashingSparseModel(), new NoopRerankModel(), agentService);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of());
        when(sessionStore.load(anyString())).thenReturn(List.of());
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("你好，我是智能知识库助手")).build());

        RagChatResponse response = service.chat("未收录的问题", null);

        assertThat(response.answer()).contains("智能知识库助手");
        assertThat(response.sources()).isEmpty();
        assertThat(response.sessionId()).isNotBlank();
    }

    @Test
    void answersWithRetrievedSources() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        SessionStore sessionStore = mock(SessionStore.class);
        ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
        AgentService agentService = mock(AgentService.class);
        RagChatServiceImpl service = new RagChatServiceImpl(
                chatModel, streamingModel, embeddingModel, vectorStore, properties(),
                sessionStore, chatHistoryMapper, new HashingSparseModel(), new NoopRerankModel(), agentService);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(new VectorMatch("id", "doc", "Spring Boot 配置", "spring.md", 0, 0.91)));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("答案是 Spring Boot 配置。")).build());
        when(sessionStore.load(anyString())).thenReturn(List.of());

        RagChatResponse response = service.chat("Spring Boot 怎么配置？", "session-1");

        assertThat(response.answer()).isEqualTo("答案是 Spring Boot 配置。");
        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).score()).isEqualTo(0.91);
        verify(chatModel).chat(any(ChatRequest.class));
        verify(sessionStore, times(2)).append(eq("session-1"), any());
        verify(chatHistoryMapper, times(2)).insert(any(ChatHistoryEntity.class));
    }

    @Test
    void appliesHybridRecallAndRerankTwoStage() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        SessionStore sessionStore = mock(SessionStore.class);
        ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);
        RerankModel rerankModel = mock(RerankModel.class);
        AgentService agentService = mock(AgentService.class);
        RagChatServiceImpl service = new RagChatServiceImpl(
                chatModel, streamingModel, embeddingModel, vectorStore, hybridRerankProperties(),
                sessionStore, chatHistoryMapper, new HashingSparseModel(), rerankModel, agentService);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), any(), anyInt(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of(
                        new VectorMatch("a", "doc", "候选 A", "a.txt", 0, 0.5),
                        new VectorMatch("b", "doc", "候选 B", "b.txt", 1, 0.4)));
        when(rerankModel.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new VectorMatch("b", "doc", "候选 B", "b.txt", 1, 0.95)));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("答案")).build());
        when(sessionStore.load(anyString())).thenReturn(List.of());

        RagChatResponse response = service.chat("问题", "session-2");

        verify(vectorStore).search(any(), any(), anyInt(), anyDouble(), anyInt(), any());
        verify(rerankModel).rerank(anyString(), anyList(), anyInt());
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).text()).isEqualTo("候选 B");
        assertThat(response.sources().get(0).score()).isEqualTo(0.95);
    }

    private RagProperties properties() {
        return new RagProperties(
                new RagProperties.Chunking(50, 10),
                new RagProperties.Retrieval(3, 0),
                new RagProperties.VectorStore("memory", "http://localhost:6333", "knowledge"),
                new RagProperties.Embedding("hash", 768, "text-embedding-3-small", "http://localhost", ""),
                new RagProperties.Upload(1024, List.of("txt", "md", "markdown")),
                new RagProperties.Hybrid(false, 20, 60),
                new RagProperties.Rerank(false, "noop", "", "", "", 0),
                new RagProperties.Adaptive(false, 1, 0.35),
                new RagProperties.MultiAgent(false, 2),
                new RagProperties.WebSearch(false, "tavily", "", 5, 0.35));
    }

    private RagProperties hybridRerankProperties() {
        return new RagProperties(
                new RagProperties.Chunking(50, 10),
                new RagProperties.Retrieval(3, 0),
                new RagProperties.VectorStore("memory", "http://localhost:6333", "knowledge"),
                new RagProperties.Embedding("hash", 768, "text-embedding-3-small", "http://localhost", ""),
                new RagProperties.Upload(1024, List.of("txt", "md", "markdown")),
                new RagProperties.Hybrid(true, 20, 60),
                new RagProperties.Rerank(true, "noop", "", "", "", 1),
                new RagProperties.Adaptive(false, 1, 0.35),
                new RagProperties.MultiAgent(false, 2),
                new RagProperties.WebSearch(false, "tavily", "", 5, 0.35));
    }
}
