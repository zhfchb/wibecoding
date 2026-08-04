package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryVectorStoreTest {

    private final InMemoryVectorStore store = new InMemoryVectorStore();

    @Test
    void findsClosestChunk() {
        store.add(chunk("第一段", new float[]{1, 0, 0}));
        store.add(chunk("第二段", new float[]{0, 1, 0}));

        List<VectorMatch> matches = store.search(new float[]{1, 0, 0}, 1, 0);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).text()).isEqualTo("第一段");
    }

    @Test
    void filtersByMinimumScore() {
        store.add(chunk("相似", new float[]{1, 0, 0}));

        assertThat(store.search(new float[]{1, 0, 0}, 10, 0.99)).isNotEmpty();
        assertThat(store.search(new float[]{0, 1, 0}, 10, 0.5)).isEmpty();
    }

    @Test
    void deletesByDocumentId() {
        String documentId = UUID.randomUUID().toString();
        store.add(new ChunkRecord("chunk-id", documentId, "文本", new float[]{1, 0, 0}));

        store.deleteByDocumentId(documentId);

        assertThat(store.search(new float[]{1, 0, 0}, 10, 0)).isEmpty();
    }

    private ChunkRecord chunk(String text, float[] vector) {
        return new ChunkRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), text, vector);
    }
}
