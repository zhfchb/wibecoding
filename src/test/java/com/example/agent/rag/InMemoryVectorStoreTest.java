package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryVectorStoreTest {

    private final InMemoryVectorStore store = new InMemoryVectorStore();
    private final SparseEmbeddingModel sparseModel = new HashingSparseModel();

    @Test
    void findsClosestChunk() {
        store.add(chunk("第一段", new float[]{1, 0, 0}));
        store.add(chunk("第二段", new float[]{0, 1, 0}));

        List<VectorMatch> matches = store.search(new float[]{1, 0, 0}, 1, 0, null);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).text()).isEqualTo("第一段");
    }

    @Test
    void filtersByMinimumScore() {
        store.add(chunk("相似", new float[]{1, 0, 0}));

        assertThat(store.search(new float[]{1, 0, 0}, 10, 0.99, null)).isNotEmpty();
        assertThat(store.search(new float[]{0, 1, 0}, 10, 0.5, null)).isEmpty();
    }

    @Test
    void deletesByDocumentId() {
        String documentId = UUID.randomUUID().toString();
        store.add(new ChunkRecord("chunk-id", documentId, "文本", "f.txt", 0, new float[]{1, 0, 0}, SparseEmbedding.empty(), null));

        store.deleteByDocumentId(documentId);

        assertThat(store.search(new float[]{1, 0, 0}, 10, 0, null)).isEmpty();
    }

    @Test
    void hybridSearchFusesDenseAndSparse() {
        // 文档 A：稠密向量与查询正交（稠密路不命中），但关键词高度重合（稀疏路命中）
        store.add(chunk("Spring Boot 自动配置原理", new float[]{0, 1, 0}));
        // 文档 B：稠密向量与查询一致（稠密路命中），但关键词不重合
        store.add(chunk("完全无关的内容 xyz", new float[]{1, 0, 0}));

        float[] queryDense = new float[]{1, 0, 0};
        SparseEmbedding querySparse = sparseModel.embed("Spring Boot 自动配置");

        List<VectorMatch> matches = store.search(queryDense, querySparse, 5, 0.0, 60, null);

        // 两篇都应被召回（各自被一条路召回），RRF 融合后都出现
        assertThat(matches).hasSize(2);
        assertThat(matches).extracting(VectorMatch::text)
                .containsExactlyInAnyOrder("Spring Boot 自动配置原理", "完全无关的内容 xyz");
    }

    @Test
    void hybridSearchDegradestoDenseWhenSparseEmpty() {
        store.add(chunk("第一段", new float[]{1, 0, 0}));

        List<VectorMatch> matches = store.search(new float[]{1, 0, 0}, SparseEmbedding.empty(), 5, 0.0, 60, null);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).text()).isEqualTo("第一段");
    }

    private ChunkRecord chunk(String text, float[] vector) {
        return new ChunkRecord(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                text,
                "test.txt",
                0,
                vector,
                sparseModel.embed(text),
                null);
    }
}
