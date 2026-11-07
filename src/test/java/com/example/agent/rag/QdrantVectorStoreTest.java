package com.example.agent.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QdrantVectorStore 集成测试：依赖本地 docker compose 启动的 Qdrant 容器。
 * 默认跳过，避免 CI 在没有 Qdrant 时失败；本地验证时设置环境变量 QDRANT_URL=http://localhost:6333 启用。
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_URL", matches = ".+")
class QdrantVectorStoreTest {

    private static final int DIMENSION = 4;

    private String collectionName;
    private String qdrantUrl;
    private QdrantVectorStore store;

    @BeforeEach
    void setUp() {
        qdrantUrl = System.getenv("QDRANT_URL");
        collectionName = "knowledge_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        store = new QdrantVectorStore(qdrantUrl, collectionName, DIMENSION);
    }

    @AfterEach
    void tearDown() {
        // 清理测试集合，避免残留数据
        RestClient.builder().baseUrl(qdrantUrl).build()
                .delete()
                .uri("/collections/{collection}", collectionName)
                .retrieve()
                .toBodilessEntity();
    }

    @Test
    void addAndSearchFindsInsertedChunk() {
        String documentId = UUID.randomUUID().toString();
        store.add(new ChunkRecord(UUID.randomUUID().toString(), documentId, "Spring Boot 配置 Qdrant", "spring.md", 0, unitVector(0), SparseEmbedding.empty(), null));

        List<VectorMatch> matches = store.search(unitVector(0), 5, 0.0, null);

        assertThat(matches).anySatisfy(match -> {
            assertThat(match.text()).isEqualTo("Spring Boot 配置 Qdrant");
            assertThat(match.documentId()).isEqualTo(documentId);
        });
    }

    @Test
    void deleteByDocumentIdRemovesAllChunks() {
        String documentId = UUID.randomUUID().toString();
        store.add(new ChunkRecord(UUID.randomUUID().toString(), documentId, "文本 A", "a.txt", 0, unitVector(1), SparseEmbedding.empty(), null));
        store.add(new ChunkRecord(UUID.randomUUID().toString(), documentId, "文本 B", "b.txt", 1, unitVector(1), SparseEmbedding.empty(), null));

        store.deleteByDocumentId(documentId);

        assertThat(store.search(unitVector(1), 10, 0.0, null))
                .noneSatisfy(match -> assertThat(match.documentId()).isEqualTo(documentId));
    }

    @Test
    void searchRespectsTopKLimit() {
        String documentId = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            store.add(new ChunkRecord(UUID.randomUUID().toString(), documentId, "文本 " + i, "f.txt", i, unitVector(0), SparseEmbedding.empty(), null));
        }

        List<VectorMatch> matches = store.search(unitVector(0), 2, 0.0, null);

        assertThat(matches).hasSize(2);
    }

    private float[] unitVector(int axis) {
        float[] vector = new float[DIMENSION];
        vector[axis % DIMENSION] = 1f;
        return vector;
    }

    @Test
    void hybridSearchKeepsLowRrfScoresAndFiltersOtherOwners() {
        var sparse = new SparseEmbedding(new int[]{12}, new float[]{1f});
        store.add(new ChunkRecord(UUID.randomUUID().toString(), "owned", "owner seven",
                "owned.md", 0, unitVector(0), sparse, 7L));
        store.add(new ChunkRecord(UUID.randomUUID().toString(), "other", "owner eight",
                "other.md", 0, unitVector(0), sparse, 8L));

        var matches = store.search(unitVector(0), sparse, 5, 0.2, 60, 7L);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).documentId()).isEqualTo("owned");
        assertThat(matches.get(0).score()).isLessThan(0.2);
    }
}
