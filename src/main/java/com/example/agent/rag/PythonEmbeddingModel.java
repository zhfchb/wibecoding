package com.example.agent.rag;

import com.example.agent.service.AiGateway;
import java.util.*;

public class PythonEmbeddingModel implements EmbeddingModel {
    private final AiGateway gateway;
    private final int dimension;
    public PythonEmbeddingModel(AiGateway gateway, int dimension) {
        this.gateway = gateway;
        this.dimension = dimension;
    }
    public int dimension() { return dimension; }
    public float[] embed(String text) { return embedAll(List.of(text)).get(0); }
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += 32) {
            var batch = texts.subList(from, Math.min(from + 32, texts.size()));
            var vectors = gateway.post("/internal/v1/embeddings", Map.of("texts", batch)).path("vectors");
            if (!vectors.isArray() || vectors.size() != batch.size()) throw new IllegalStateException("Embedding count mismatch");
            for (var vector : vectors) {
                if (!vector.isArray() || vector.size() != dimension) throw new IllegalStateException("Embedding dimension mismatch");
                float[] values = new float[dimension];
                for (int i = 0; i < dimension; i++) {
                    if (!vector.get(i).isNumber()) throw new IllegalStateException("Invalid embedding value");
                    values[i] = (float)vector.get(i).asDouble();
                    if (!Float.isFinite(values[i])) throw new IllegalStateException("Non-finite embedding");
                }
                result.add(values);
            }
        }
        return result;
    }
}
