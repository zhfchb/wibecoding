package com.example.agent.rag;

import com.example.agent.rag.EmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LexicalHashEmbeddingModelTest {

    private final EmbeddingModel model = new LexicalHashEmbeddingModel(768);

    @Test
    void similarTextHasHigherScoreThanUnrelatedText() {
        float[] related = model.embed("Spring Boot 配置 Qdrant 向量库");
        float[] sameTopic = model.embed("Spring Boot 连接 Qdrant 检索");
        float[] unrelated = model.embed("今天天气很好适合散步");

        assertThat(cosine(related, sameTopic))
                .isGreaterThan(cosine(related, unrelated));
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
