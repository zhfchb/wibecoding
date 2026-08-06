package com.example.agent.rag;

/**
 * 稀疏向量表示：由词项哈希到固定索引空间，values 为对应权重（如 TF-IDF）。
 * 用于 BM25 风格的关键词检索，与稠密向量互补，配合 RRF 融合实现混合检索。
 */
public record SparseEmbedding(int[] indices, float[] values) {

    public SparseEmbedding {
        if (indices == null || values == null) {
            throw new IllegalArgumentException("indices/values 不能为空");
        }
        if (indices.length != values.length) {
            throw new IllegalArgumentException("indices 与 values 长度必须一致");
        }
    }

    public static SparseEmbedding empty() {
        return new SparseEmbedding(new int[0], new float[0]);
    }

    public boolean isEmpty() {
        return indices.length == 0;
    }
}
