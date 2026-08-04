package com.example.agent.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LexicalHashEmbeddingModelTest {

    private final EmbeddingModel model = new LexicalHashEmbeddingModel(768);

    @Test
    void similarTextHasHigherScoreThanUnrelatedText() {
        Embedding related = model.embed("Spring Boot 配置 Qdrant 向量库").content();
        Embedding sameTopic = model.embed("Spring Boot 连接 Qdrant 检索").content();
        Embedding unrelated = model.embed("今天天气很好适合散步").content();

        assertThat(cosine(related.vector(), sameTopic.vector()))
                .isGreaterThan(cosine(related.vector(), unrelated.vector()));
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
