package com.example.agent.service.impl;

import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.domain.chat.RagSource;
import com.example.agent.rag.RerankModel;
import com.example.agent.rag.SparseEmbeddingModel;
import com.example.agent.rag.VectorMatch;
import com.example.agent.rag.VectorStore;
import com.example.agent.rag.adaptive.AdaptiveRagGraphFactory;
import com.example.agent.rag.adaptive.AdaptiveRagState;
import com.example.agent.service.AdaptiveRagService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.agent.rag.adaptive.AdaptiveRagGraphFactory.*;
import static com.example.agent.rag.adaptive.AdaptiveRagState.*;

/**
 * 自适应 RAG 服务实现（M8）：基于 LangGraph4j 状态图编排
 * 「查询重写 → 检索 → 质量评分 → 重试/生成」循环。
 *
 * <p>图流转逻辑见 {@link AdaptiveRagGraphFactory} 类注释。
 * 检索逻辑（混合检索 + rerank）复用 M7 的 {@code RagChatServiceImpl} 同款配置，
 * 保持自适应链路继承 M7 的检索能力。
 */
@Slf4j
@Service
public class AdaptiveRagServiceImpl implements AdaptiveRagService {

    private static final String REWRITE_PROMPT_TEMPLATE = """
            请用不同表述方式重写以下问题，保留核心语义但换用更利于检索的关键词。
            只输出重写后的问题，不要解释、不要加引号。

            原问题：%s""";

    private static final String GENERATE_PROMPT_TEMPLATE = """
            你是一个智能知识库助手。请优先根据知识库内容回答问题。
            如果知识库内容不足以确认答案，请明确说明无法从现有知识中得到结论，不要编造。

            知识库内容：
            %s

            问题：%s""";

    private final OpenAiChatModel openAiChatModel;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final SparseEmbeddingModel sparseEmbeddingModel;
    private final RerankModel rerankModel;
    private final RagProperties.Adaptive adaptive;
    private final CompiledGraph<AdaptiveRagState> graph;

    public AdaptiveRagServiceImpl(
            OpenAiChatModel openAiChatModel,
            EmbeddingModel embeddingModel,
            VectorStore vectorStore,
            RagProperties ragProperties,
            SparseEmbeddingModel sparseEmbeddingModel,
            RerankModel rerankModel,
            AdaptiveRagGraphFactory graphFactory) {
        this.openAiChatModel = openAiChatModel;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.sparseEmbeddingModel = sparseEmbeddingModel;
        this.rerankModel = rerankModel;
        this.adaptive = ragProperties.adaptive() != null
                ? ragProperties.adaptive()
                : new RagProperties.Adaptive(false, 1, 0.35);
        this.graph = buildGraph(graphFactory, this.adaptive.maxRetries());
        log.info("自适应 RAG 状态图已构建: enabled={} maxRetries={} minScore={}",
                this.adaptive.enabled(), this.adaptive.maxRetries(), this.adaptive.minScore());
    }

    @Override
    public RagChatResponse chat(String message, String sessionId) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String session = StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();

        Map<String, Object> inputs = Map.of(QUESTION, message);
        AdaptiveRagState finalState = graph.invoke(inputs)
                .orElseThrow(() -> new IllegalStateException("自适应 RAG 状态图执行未产生结果"));

        List<RagSource> sources = finalState.documents().stream()
                .map(m -> new RagSource(m.text(), m.fileName(), m.chunkIndex(), m.score()))
                .toList();
        return new RagChatResponse(session, finalState.generation(), sources);
    }

    // ==================== 图构建 ====================

    private CompiledGraph<AdaptiveRagState> buildGraph(AdaptiveRagGraphFactory factory, int maxRetries) {
        NodeAction<AdaptiveRagState> rewrite = this::rewriteNode;
        NodeAction<AdaptiveRagState> retrieve = this::retrieveNode;
        NodeAction<AdaptiveRagState> grade = this::gradeNode;
        NodeAction<AdaptiveRagState> generate = this::generateNode;
        NodeAction<AdaptiveRagState> fallback = this::fallbackNode;
        EdgeAction<AdaptiveRagState> route = state -> routeAfterGrade(state, maxRetries);
        return factory.build(rewrite, retrieve, grade, generate, fallback, route);
    }

    // ==================== 节点动作 ====================

    /**
     * 查询重写节点：首次直接用原问题；重试时调 LLM 重写以提升检索召回。
     */
    private Map<String, Object> rewriteNode(AdaptiveRagState state) {
        if (state.retryCount() == 0) {
            return Map.of(REWRITTEN_QUESTION, state.question());
        }
        String prompt = String.format(REWRITE_PROMPT_TEMPLATE, state.question());
        String rewritten = chat(prompt);
        log.debug("查询重写 retry={}: {} → {}", state.retryCount(), state.question(), rewritten);
        return Map.of(REWRITTEN_QUESTION, rewritten);
    }

    /**
     * 检索节点：用重写后的问题做混合检索 + rerank，取 top1 分数作为质量信号。
     */
    private Map<String, Object> retrieveNode(AdaptiveRagState state) {
        String query = state.rewrittenQuestion();
        List<VectorMatch> documents = retrieve(query);
        double topScore = documents.isEmpty() ? 0.0 : documents.get(0).score();
        log.debug("检索 query={} hits={} topScore={}", query, documents.size(), topScore);
        return Map.of(DOCUMENTS, documents, RETRIEVAL_SCORE, topScore);
    }

    /**
     * 评分节点：top1 分数 ≥ 阈值且非空 → accepted；同时 retryCount +1。
     */
    private Map<String, Object> gradeNode(AdaptiveRagState state) {
        boolean accepted = !state.documents().isEmpty()
                && state.retrievalScore() >= adaptive.minScore();
        int newRetry = state.retryCount() + 1;
        log.debug("评分 topScore={} threshold={} accepted={} retryCount→{}",
                state.retrievalScore(), adaptive.minScore(), accepted, newRetry);
        return Map.of(ACCEPTED, accepted, RETRY_COUNT, newRetry);
    }

    /**
     * 生成节点：基于检索结果调 LLM 生成最终回答。
     */
    private Map<String, Object> generateNode(AdaptiveRagState state) {
        String context = buildContext(state.documents());
        String prompt = String.format(GENERATE_PROMPT_TEMPLATE, context, state.question());
        String answer = chat(prompt);
        return Map.of(GENERATION, answer);
    }

    /**
     * 兜底节点：检索质量不足且重试耗尽时，给出无法回答的提示。
     */
    private Map<String, Object> fallbackNode(AdaptiveRagState state) {
        String answer = "抱歉，知识库中未检索到与「" + state.question()
                + "」高度相关的内容。请尝试换一种问法，或先上传相关文档。";
        return Map.of(GENERATION, answer);
    }

    /**
     * 评分后路由：
     * <ul>
     *   <li>accepted → {@link #GENERATE}</li>
     *   <li>not accepted & retryCount ≤ maxRetries → {@link #REWRITE_QUERY}（重写重试）</li>
     *   <li>not accepted & retryCount &gt; maxRetries → {@link #GENERATE_FALLBACK}（兜底）</li>
     * </ul>
     */
    private String routeAfterGrade(AdaptiveRagState state, int maxRetries) {
        if (state.accepted()) {
            return GENERATE;
        }
        if (state.retryCount() <= maxRetries) {
            return REWRITE_QUERY;
        }
        return GENERATE_FALLBACK;
    }

    // ==================== 检索与生成辅助 ====================

    /**
     * 混合检索 + rerank，逻辑与 {@code RagChatServiceImpl.retrieve} 一致，
     * 确保自适应链路继承 M7 的检索能力。
     */
    private List<VectorMatch> retrieve(String message) {
        Embedding queryEmbedding = embeddingModel.embed(message).content();
        float[] dense = queryEmbedding.vector();

        RagProperties.Hybrid hybrid = ragProperties.hybrid();
        RagProperties.Rerank rerank = ragProperties.rerank();
        boolean hybridEnabled = hybrid != null && hybrid.enabled();
        double minScore = ragProperties.retrieval().minScore();
        int recallTopK = hybridEnabled
                ? hybrid.recallTopK()
                : ragProperties.retrieval().topK();
        int rrfK = hybrid != null ? hybrid.rrfK() : 60;

        List<VectorMatch> candidates = hybridEnabled
                ? vectorStore.search(dense, sparseEmbeddingModel.embed(message), recallTopK, minScore, rrfK)
                : vectorStore.search(dense, recallTopK, minScore);

        int finalTopK = (rerank != null && rerank.topK() > 0)
                ? rerank.topK()
                : ragProperties.retrieval().topK();
        boolean rerankEnabled = rerank != null && rerank.enabled();
        if (rerankEnabled) {
            return rerankModel.rerank(message, candidates, finalTopK);
        }
        return candidates.size() <= finalTopK ? candidates : candidates.subList(0, finalTopK);
    }

    private String buildContext(List<VectorMatch> matches) {
        StringBuilder builder = new StringBuilder("知识库内容：\n");
        for (int i = 0; i < matches.size(); i++) {
            builder.append('[').append(i + 1).append("] ")
                    .append(matches.get(i).text()).append('\n');
        }
        return builder.toString();
    }

    private String chat(String prompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build();
        return openAiChatModel.chat(request).aiMessage().text();
    }
}
