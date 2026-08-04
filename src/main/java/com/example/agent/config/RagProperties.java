package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Chunking chunking,
        Retrieval retrieval,
        VectorStore vectorStore,
        Embedding embedding,
        Upload upload) {

    public record Chunking(int maxChars, int overlapChars) {
    }

    public record Retrieval(int topK, double minScore) {
    }

    public record VectorStore(String type, String qdrantUrl, String collectionName) {
    }

    public record Embedding(String provider, int dimension, String modelName, String baseUrl, String apiKey) {
    }

    public record Upload(long maxFileSizeBytes, List<String> allowedExtensions) {
    }
}
