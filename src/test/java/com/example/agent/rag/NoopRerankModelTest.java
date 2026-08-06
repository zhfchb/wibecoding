package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoopRerankModelTest {

    private final NoopRerankModel model = new NoopRerankModel();

    @Test
    void truncatesToTopK() {
        List<VectorMatch> candidates = List.of(
                match("a", 0.9), match("b", 0.8), match("c", 0.7));

        List<VectorMatch> result = model.rerank("query", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(VectorMatch::text).containsExactly("a", "b");
    }

    @Test
    void keepsAllWhenFewerThanTopK() {
        List<VectorMatch> candidates = List.of(match("a", 0.9));

        List<VectorMatch> result = model.rerank("query", candidates, 3);

        assertThat(result).hasSize(1);
    }

    @Test
    void returnsEmptyForEmptyInput() {
        assertThat(model.rerank("query", List.of(), 3)).isEmpty();
    }

    private VectorMatch match(String text, double score) {
        return new VectorMatch(text, "doc", text, "f.txt", 0, score);
    }
}
