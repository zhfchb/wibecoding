package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingSparseModelTest {

    private final HashingSparseModel model = new HashingSparseModel();

    @Test
    void embedsNonEmptyText() {
        SparseEmbedding sparse = model.embed("Spring Boot 自动配置");

        assertThat(sparse.isEmpty()).isFalse();
        assertThat(sparse.indices().length).isEqualTo(sparse.values().length);
        assertThat(sparse.indices().length).isGreaterThan(0);
    }

    @Test
    void returnsEmptyForBlank() {
        assertThat(model.embed("").isEmpty()).isTrue();
        assertThat(model.embed("   ").isEmpty()).isTrue();
        assertThat(model.embed(null).isEmpty()).isTrue();
    }

    @Test
    void isDeterministicAndDiffersForDifferentText() {
        SparseEmbedding a1 = model.embed("Docker 容器部署");
        SparseEmbedding a2 = model.embed("Docker 容器部署");
        SparseEmbedding b = model.embed("Java 17 新特性");

        assertThat(a1.indices()).isEqualTo(a2.indices());
        assertThat(a1.indices()).isNotEqualTo(b.indices());
    }

    @Test
    void overlappingTokensProduceNonZeroDotProduct() {
        SparseEmbedding query = model.embed("Spring Boot 配置");
        SparseEmbedding doc = model.embed("Spring Boot 自动配置原理");

        // 手工计算稀疏点积：应 > 0（共享 Spring/Boot/配置 等词项）
        double dot = dot(query, doc);
        assertThat(dot).isGreaterThan(0);
    }

    private double dot(SparseEmbedding a, SparseEmbedding b) {
        java.util.Map<Integer, Float> map = new java.util.HashMap<>();
        for (int i = 0; i < a.indices().length; i++) {
            map.put(a.indices()[i], a.values()[i]);
        }
        double sum = 0;
        for (int i = 0; i < b.indices().length; i++) {
            Float q = map.get(b.indices()[i]);
            if (q != null) {
                sum += q * b.values()[i];
            }
        }
        return sum;
    }
}
