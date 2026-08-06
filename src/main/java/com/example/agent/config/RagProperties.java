package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Chunking chunking,
        Retrieval retrieval,
        VectorStore vectorStore,
        Embedding embedding,
        Upload upload,
        Hybrid hybrid,
        Rerank rerank,
        Adaptive adaptive,
        MultiAgent multiAgent) {

    public record Chunking(int maxChars, int overlapChars) {
    }

    public record Retrieval(int topK, double minScore) {
    }

    public record VectorStore(String type, String qdrantUrl, String collectionName) {
    }

    public record Embedding(String provider, int dimension, String modelName, String baseUrl, String apiKey) {
    }

    public record Upload(long maxFileSizeBytes, List<String> allowedExtensions) {
    }

    /**
     * 混合检索配置。
     *
     * @param enabled     是否启用稠密+稀疏混合检索
     * @param recallTopK  混合召回数量（精排前候选数，建议 20）
     * @param rrfK        RRF 融合常数 k，通常 60
     */
    public record Hybrid(boolean enabled, int recallTopK, int rrfK) {
    }

    /**
     * Rerank 精排配置。
     *
     * @param enabled   是否启用 rerank
     * @param provider  noop：直接截断；siliconflow：调硅基流动 BGE-Reranker
     * @param baseUrl   rerank API 地址
     * @param apiKey    rerank API Key
     * @param modelName 模型名，如 BAAI/bge-reranker-v2-m3
     * @param topK      精排后保留数量（0 表示用 retrieval.topK）
     */
    public record Rerank(boolean enabled, String provider, String baseUrl, String apiKey, String modelName, int topK) {
    }

    /**
     * 自适应 RAG 配置（M8）：基于 LangGraph4j 状态图的查询重写→检索→评分→重试/生成循环。
     *
     * @param enabled    是否启用自适应 RAG 链路
     * @param maxRetries 查询重写最大重试次数（0=仅检索一次不重写，1=允许 1 次重写后兜底）
     * @param minScore   检索质量阈值；top1 score 低于此值视为检索不足，触发重写或兜底
     */
    public record Adaptive(boolean enabled, int maxRetries, double minScore) {
    }

    /**
     * 多 Agent 协作配置（M9）：检索 Agent → 生成 Agent → HITL 人工审核。
     *
     * @param enabled     是否启用多 Agent 工作流
     * @param maxRejects  最多被拒绝次数，超过后强制通过（避免无限循环），默认 2
     */
    public record MultiAgent(boolean enabled, int maxRejects) {
    }
}
