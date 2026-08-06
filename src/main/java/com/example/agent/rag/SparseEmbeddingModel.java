package com.example.agent.rag;

/**
 * 稀疏向量编码模型：把文本转成 {@link SparseEmbedding}，用于关键词层面的召回。
 * 与稠密 Embedding 模型分离，避免侵入 LangChain4j 的 EmbeddingModel 抽象。
 */
public interface SparseEmbeddingModel {

    SparseEmbedding embed(String text);
}
