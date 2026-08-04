package com.example.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QdrantVectorStore implements VectorStore {

    private final String collectionName;
    private final int dimension;
    private final RestClient restClient;

    public QdrantVectorStore(String baseUrl, String collectionName, int dimension) {
        this.collectionName = collectionName;
        this.dimension = dimension;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public synchronized void add(ChunkRecord chunk) {
        ensureCollection();
        Map<String, Object> point = Map.of(
                "id", chunk.id(),
                "vector", toList(chunk.vector()),
                "payload", Map.of(
                        "text", chunk.text(),
                        "document_id", chunk.documentId()));
        Map<String, Object> body = Map.of("points", List.of(point));

        restClient.put()
                .uri("/collections/{collection}/points?wait=true", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public synchronized List<VectorMatch> search(float[] queryVector, int topK, double minScore) {
        ensureCollection();
        Map<String, Object> body = Map.of(
                "vector", toList(queryVector),
                "limit", topK,
                "score_threshold", minScore,
                "with_payload", true);

        JsonNode root = restClient.post()
                .uri("/collections/{collection}/points/search", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        List<VectorMatch> matches = new ArrayList<>();
        if (root != null && root.has("result")) {
            for (JsonNode node : root.path("result")) {
                JsonNode payload = node.path("payload");
                matches.add(new VectorMatch(
                        node.path("id").asText(),
                        payload.path("document_id").asText(),
                        payload.path("text").asText(),
                        node.path("score").asDouble()));
            }
        }
        return matches.stream()
                .filter(match -> match.score() >= minScore)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(topK)
                .toList();
    }

    @Override
    public synchronized void deleteByDocumentId(String documentId) {
        Map<String, Object> match = Map.of("key", "document_id", "match", Map.of("value", documentId));
        Map<String, Object> body = Map.of("filter", Map.of("must", List.of(match)));

        restClient.post()
                .uri("/collections/{collection}/points/delete?wait=true", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private void ensureCollection() {
        Map<String, Object> body = Map.of(
                "vectors", Map.of(
                        "size", dimension,
                        "distance", "Cosine"));
        try {
            restClient.put()
                    .uri("/collections/{collection}", collectionName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) {
                throw e;
            }
        }
    }

    private List<Float> toList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }
}
