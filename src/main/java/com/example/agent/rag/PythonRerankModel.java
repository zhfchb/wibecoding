package com.example.agent.rag;

import com.example.agent.service.AiGateway;
import java.util.*;

public class PythonRerankModel implements RerankModel {
    private final AiGateway gateway;
    public PythonRerankModel(AiGateway gateway) { this.gateway = gateway; }
    public List<VectorMatch> rerank(String query, List<VectorMatch> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;
        try {
            var result = gateway.post("/internal/v1/rerank", Map.of("query", query,
                "documents", candidates.stream().map(VectorMatch::text).toList(), "topK", topK));
            List<VectorMatch> ranked = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (var item : result.path("results")) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(Double.NaN);
                if (index < 0 || index >= candidates.size() || !Double.isFinite(score) || !seen.add(index)) {
                    throw new IllegalStateException("Invalid rerank response");
                }
                var origin = candidates.get(index);
                ranked.add(new VectorMatch(origin.id(), origin.documentId(), origin.text(), origin.fileName(), origin.chunkIndex(), score));
            }
            if (!ranked.isEmpty()) return ranked.subList(0, Math.min(topK, ranked.size()));
        } catch (Exception ignored) { /* Preserve original recall order when reranking is unavailable. */ }
        return candidates.subList(0, Math.min(topK, candidates.size()));
    }
}
