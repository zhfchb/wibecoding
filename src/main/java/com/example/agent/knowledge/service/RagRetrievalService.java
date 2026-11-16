package com.example.agent.knowledge.service;

import com.example.agent.knowledge.config.RagProperties;
import com.example.agent.knowledge.rag.EmbeddingModel;
import com.example.agent.knowledge.rag.RerankModel;
import com.example.agent.knowledge.rag.SparseEmbeddingModel;
import com.example.agent.knowledge.rag.VectorMatch;
import com.example.agent.knowledge.rag.VectorStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Scoped data access for Python orchestration. No model generation here. */
@Service
@RequiredArgsConstructor
public class RagRetrievalService {
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final SparseEmbeddingModel sparseEmbeddingModel;
    private final RerankModel rerankModel;
    private final DocumentLifecycle lifecycle;

    public List<VectorMatch> retrieve(String query, Long ownerId) {
        if (ownerId == null || ownerId <= 0) throw new IllegalArgumentException("ownerId required");
        float[] dense = embeddingModel.embed(query);
        var hybrid = properties.hybrid();
        var rerank = properties.rerank();
        boolean enabled = hybrid != null && hybrid.enabled();
        int recall = enabled ? hybrid.recallTopK() : properties.retrieval().topK();
        var candidates = enabled
                ? vectorStore.search(dense, sparseEmbeddingModel.embed(query), recall,
                    properties.retrieval().minScore(), hybrid.rrfK(), ownerId)
                : vectorStore.search(dense, recall, properties.retrieval().minScore(), ownerId);
        int topK = rerank != null && rerank.topK() > 0 ? rerank.topK() : properties.retrieval().topK();
        candidates = lifecycle.visible(candidates, ownerId);
        return rerank != null && rerank.enabled() ? rerankModel.rerank(query, candidates, topK)
                : candidates.subList(0, Math.min(topK, candidates.size()));
    }
}
