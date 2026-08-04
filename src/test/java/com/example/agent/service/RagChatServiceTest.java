package com.example.agent.service;

import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.RagChatResponse;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    @Test
    void returnsFallbackWhenNoKnowledgeFound() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        RagChatServiceImpl service = new RagChatServiceImpl(
                chatModel, streamingModel, embeddingModel, vectorStore, properties());

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble())).thenReturn(List.of());

        RagChatResponse response = service.chat("未收录的问题");

        assertThat(response.answer()).contains("没有检索到");
        assertThat(response.sources()).isEmpty();
        verify(chatModel, never()).chat(any(ChatRequest.class));
    }

    @Test
    void answersWithRetrievedSources() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);
        RagChatServiceImpl service = new RagChatServiceImpl(
                chatModel, streamingModel, embeddingModel, vectorStore, properties());

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new VectorMatch("id", "doc", "Spring Boot 配置", 0.91)));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("答案是 Spring Boot 配置。")).build());

        RagChatResponse response = service.chat("Spring Boot 怎么配置？");

        assertThat(response.answer()).isEqualTo("答案是 Spring Boot 配置。");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).score()).isEqualTo(0.91);
        verify(chatModel).chat(any(ChatRequest.class));
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
