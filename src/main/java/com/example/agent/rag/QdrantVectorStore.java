package com.example.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QdrantVectorStore implements VectorStore {

    private static final String DENSE_NAME = "dense";
    private static final String SPARSE_NAME = "sparse";

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

        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put(DENSE_NAME, toList(chunk.vector()));
        if (chunk.sparseVector() != null && !chunk.sparseVector().isEmpty()) {
            vector.put(SPARSE_NAME, Map.of(
                    "indices", toIntList(chunk.sparseVector().indices()),
                    "values", toFloatList(chunk.sparseVector().values())));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", chunk.text());
        payload.put("document_id", chunk.documentId());
        payload.put("file_name", chunk.fileName());
        payload.put("chunk_index", chunk.chunkIndex());
        payload.put("user_id", chunk.userId());

        Map<String, Object> point = Map.of(
                "id", chunk.id(),
                "vector", vector,
                "payload", payload);
        Map<String, Object> body = Map.of("points", List.of(point));

        restClient.put()
                .uri("/collections/{collection}/points?wait=true", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public synchronized List<VectorMatch> search(float[] queryVector, int topK, double minScore, Long userId) {
        return search(queryVector, null, topK, minScore, 0, userId);
    }

    @Override
    public synchronized List<VectorMatch> search(float[] denseVector,
                                                 SparseEmbedding sparseVector,
                                                 int topK,
                                                 double minScore,
                                                 int rrfK,
                                                 Long userId) {
        ensureCollection();

        // 用 LinkedHashMap 以便条件性添加 filter
        Map<String, Object> body = new LinkedHashMap<>();
        if (sparseVector == null || sparseVector.isEmpty()) {
            // 纯稠密检索：/points/query 直接 query
            body.put("query", toList(denseVector));
            body.put("using", DENSE_NAME);
            body.put("limit", topK);
            body.put("score_threshold", minScore);
            body.put("with_payload", true);
        } else {
            // 混合检索：dense + sparse 两路 prefetch，RRF 融合
            Map<String, Object> densePrefetch = Map.of(
                    "query", toList(denseVector),
                    "using", DENSE_NAME,
                    "limit", topK,
                    "score_threshold", minScore);
            Map<String, Object> sparsePrefetch = Map.of(
                    "query", Map.of(
                            "indices", toIntList(sparseVector.indices()),
                            "values", toFloatList(sparseVector.values())),
                    "using", SPARSE_NAME,
                    "limit", topK);
            body.put("prefetch", List.of(densePrefetch, sparsePrefetch));
            body.put("query", Map.of("rrf", Map.of("k", rrfK)));
            body.put("limit", topK);
            body.put("with_payload", true);
        }

        // userId 非空时加过滤条件，实现用户隔离
        if (userId != null) {
            Map<String, Object> filter = Map.of("must", List.of(
                    Map.of("key", "user_id", "match", Map.of("value", userId))));
            body.put("filter", filter);
        }

        JsonNode root = restClient.post()
                .uri("/collections/{collection}/points/query", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        // RRF scores are rank-fusion values, not cosine similarities. The
        // cosine threshold was already applied to dense prefetch above.
        boolean hybrid = sparseVector != null && !sparseVector.isEmpty();
        return parseQueryResult(root, hybrid ? 0.0 : minScore, topK);
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
        // 命名稠密向量 dense + 稀疏向量 sparse，支持原生混合检索
        Map<String, Object> body = Map.of(
                "vectors", Map.of(DENSE_NAME, Map.of(
                        "size", dimension,
                        "distance", "Cosine")),
                "sparse_vectors", Map.of(SPARSE_NAME, Map.of()));
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

    private List<VectorMatch> parseQueryResult(JsonNode root, double minScore, int topK) {
        List<VectorMatch> matches = new ArrayList<>();
        if (root != null && root.has("result")) {
            JsonNode result = root.path("result");
            // /points/query 的结果是 { points: [...] }；兼容旧 /points/search 的数组结果
            JsonNode points = result.isArray() ? result : result.path("points");
            for (JsonNode node : points) {
                JsonNode payload = node.path("payload");
                matches.add(new VectorMatch(
                        node.path("id").asText(),
                        payload.path("document_id").asText(),
                        payload.path("text").asText(),
                        payload.path("file_name").asText(null),
                        payload.path("chunk_index").asInt(0),
                        node.path("score").asDouble()));
            }
        }
        return matches.stream()
                .filter(match -> match.score() >= minScore)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(topK)
                .toList();
    }

    private List<Float> toList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }

    private List<Integer> toIntList(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    private List<Float> toFloatList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }
}
