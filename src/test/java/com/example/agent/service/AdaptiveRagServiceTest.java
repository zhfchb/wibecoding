package com.example.agent.service;

import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.rag.HashingSparseModel;
import com.example.agent.rag.NoopRerankModel;
import com.example.agent.rag.VectorMatch;
import com.example.agent.rag.VectorStore;
import com.example.agent.rag.adaptive.AdaptiveRagGraphFactory;
import com.example.agent.service.impl.AdaptiveRagServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自适应 RAG 状态图流转测试（M8）。
 *
 * <p>覆盖 4 条路由路径：
 * <ol>
 *   <li>高分直接生成：top1 ≥ 阈值 → generate（1 次 chatModel 调用）</li>
 *   <li>低分不重试兜底：maxRetries=0 + 低分 → generate_fallback（0 次 chatModel 调用）</li>
 *   <li>低分重写成功生成：maxRetries=1 + 首轮低分 + 重写后高分 → generate（2 次 chatModel 调用）</li>
 *   <li>低分重写失败兜底：maxRetries=1 + 两轮低分 → generate_fallback（1 次 chatModel 调用，仅重写）</li>
 * </ol>
 */
class AdaptiveRagServiceTest {

    private static final VectorMatch HIGH_SCORE_HIT = new VectorMatch("id", "doc", "相关内容", "file.md", 0, 0.90);
    private static final VectorMatch LOW_SCORE_HIT = new VectorMatch("id", "doc", "弱相关内容", "file.md", 0, 0.10);

    @Test
    void highScore_directGenerate() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble())).thenReturn(List.of(HIGH_SCORE_HIT));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("最终答案")).build());

        AdaptiveRagServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 1, 0.35);
        RagChatResponse response = service.chat("问题", "session-1");

        assertThat(response.answer()).isEqualTo("最终答案");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).score()).isEqualTo(0.90);
        // 仅生成节点调一次 LLM，重写节点首次不调
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void lowScore_noRetry_fallback() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble())).thenReturn(List.of(LOW_SCORE_HIT));

        AdaptiveRagServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 0, 0.35);
        RagChatResponse response = service.chat("冷门问题", "session-2");

        assertThat(response.answer()).contains("未检索到");
        assertThat(response.answer()).contains("冷门问题");
        // maxRetries=0 → 不重写不生成，直接兜底，不调 LLM
        verify(chatModel, times(0)).chat(any(ChatRequest.class));
    }

    @Test
    void lowScore_rewriteSuccess_thenGenerate() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        // 第 1 次检索低分，第 2 次（重写后）高分
        when(vectorStore.search(any(), anyInt(), anyDouble()))
                .thenReturn(List.of(LOW_SCORE_HIT))
                .thenReturn(List.of(HIGH_SCORE_HIT));
        // 第 1 次 chat 调用：重写节点返回重写后的问题；第 2 次：生成节点返回答案
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("重写后的问题")).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("最终答案")).build());

        AdaptiveRagServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 1, 0.35);
        RagChatResponse response = service.chat("原始问题", "session-3");

        assertThat(response.answer()).isEqualTo("最终答案");
        assertThat(response.sources()).hasSize(1);
        // 2 次 chat 调用：1 次重写 + 1 次生成
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void lowScore_rewriteFail_thenFallback() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        // 两次检索都低分
        when(vectorStore.search(any(), anyInt(), anyDouble())).thenReturn(List.of(LOW_SCORE_HIT));
        // 仅重写节点会调 1 次 chat
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("重写后的问题")).build());

        AdaptiveRagServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 1, 0.35);
        RagChatResponse response = service.chat("无解问题", "session-4");

        assertThat(response.answer()).contains("未检索到");
        assertThat(response.answer()).contains("无解问题");
        // 1 次 chat 调用：仅重写；生成节点未被触发，兜底不调 LLM
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void emptyRetrieval_triggersFallback() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble())).thenReturn(List.of());
        // maxRetries=1：空检索→重写→仍空→兜底，重写节点会调 1 次 chat
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("重写后的问题")).build());

        AdaptiveRagServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 1, 0.35);
        RagChatResponse response = service.chat("空检索问题", "session-5");

        assertThat(response.answer()).contains("未检索到");
        // 1 次 chat 调用：仅重写；两轮空检索后走兜底
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    private AdaptiveRagServiceImpl newService(OpenAiChatModel chatModel,
                                              EmbeddingModel embeddingModel,
                                              VectorStore vectorStore,
                                              int maxRetries,
                                              double minScore) {
        return new AdaptiveRagServiceImpl(
                chatModel,
                embeddingModel,
                vectorStore,
                properties(maxRetries, minScore),
                new HashingSparseModel(),
                new NoopRerankModel(),
                new AdaptiveRagGraphFactory());
    }

    private RagProperties properties(int maxRetries, double minScore) {
        return new RagProperties(
                new RagProperties.Chunking(50, 10),
                new RagProperties.Retrieval(3, 0),
                new RagProperties.VectorStore("memory", "http://localhost:6333", "knowledge"),
                new RagProperties.Embedding("hash", 768, "text-embedding-3-small", "http://localhost", ""),
                new RagProperties.Upload(1024, List.of("txt", "md", "markdown")),
                new RagProperties.Hybrid(false, 20, 60),
                new RagProperties.Rerank(false, "noop", "", "", "", 0),
                new RagProperties.Adaptive(true, maxRetries, minScore),
                new RagProperties.MultiAgent(false, 2));
    }
}
