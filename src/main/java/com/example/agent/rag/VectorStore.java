package com.example.agent.rag;

import java.util.List;

public interface VectorStore {

    void add(ChunkRecord chunk);

    /**
     * 纯稠密向量检索。
     */
    List<VectorMatch> search(float[] queryVector, int topK, double minScore);

    /**
     * 混合检索：稠密 + 稀疏（BM25 风格）两路召回，用 RRF（Reciprocal Rank Fusion）融合。
     * 当 {@code sparseVector} 为 null 或空时退化为纯稠密检索。
     *
     * @param denseVector  查询稠密向量
     * @param sparseVector 查询稀疏向量（可为 null）
     * @param topK         最终返回数量
     * @param minScore     最小分数阈值（作用于稠密路）
     * @param rrfK         RRF 常数 k，通常取 60
     */
    List<VectorMatch> search(float[] denseVector, SparseEmbedding sparseVector, int topK, double minScore, int rrfK);

    void deleteByDocumentId(String documentId);
}
