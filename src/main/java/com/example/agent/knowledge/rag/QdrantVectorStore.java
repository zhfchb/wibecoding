package com.example.agent.knowledge.rag;

import com.example.agent.exception.VectorStoreUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@lombok.extern.slf4j.Slf4j
public class QdrantVectorStore implements VectorStore {

    private static final String DENSE_NAME = "dense";
    private static final String SPARSE_NAME = "sparse";

    private final String collectionName;
    private final int dimension;
    private final RestClient restClient;
    private boolean collectionReady;

    public QdrantVectorStore(String baseUrl, String collectionName, int dimension) {
        this(client(baseUrl), collectionName, dimension);
    }

    QdrantVectorStore(RestClient restClient, String collectionName, int dimension) {
        this.collectionName = collectionName;
        this.dimension = dimension;
        this.restClient = restClient;
    }

    private static RestClient client(String baseUrl) {
        var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private <T> T transport(String operation, Supplier<T> request) {
        for (int attempt = 0; ; attempt++) {
            try { return request.get(); }
            catch (ResourceAccessException failure) {
                if (attempt == 1) throw new VectorStoreUnavailableException(failure);
                log.warn("Qdrant transport interrupted; retrying once: operation={}", operation);
            }
        }
    }

    private <T> T collectionRequest(String operation, Supplier<T> request) {
        ensureCollection();
        try { return transport(operation, request); }
        catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() != 404) throw failure;
            // A previously checked collection may have been removed/recreated externally.
            collectionReady = false;
            ensureCollection();
            return transport(operation, request);
        }
    }

    @Override
    public synchronized void add(ChunkRecord chunk) {
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

        collectionRequest("upsert_points", () -> restClient.put()
                .uri("/collections/{collection}/points?wait=true", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity());
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

        JsonNode root = collectionRequest("query_points", () -> restClient.post()
                .uri("/collections/{collection}/points/query", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));

        // RRF scores are rank-fusion values, not cosine similarities. The
        // cosine threshold was already applied to dense prefetch above.
        boolean hybrid = sparseVector != null && !sparseVector.isEmpty();
        return parseQueryResult(root, hybrid ? 0.0 : minScore, topK);
    }

    @Override
    public synchronized void deleteByDocumentId(String documentId) {
        Map<String, Object> match = Map.of("key", "document_id", "match", Map.of("value", documentId));
        Map<String, Object> body = Map.of("filter", Map.of("must", List.of(match)));

        try {
            transport("delete_points", () -> restClient.post()
                .uri("/collections/{collection}/points/delete?wait=true", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity());
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() != 404) throw failure;
            collectionReady = false; // No collection means no remaining points to delete.
        }
    }

    private void ensureCollection() {
        if (collectionReady) return;
        try {
            transport("check_collection", () -> restClient.get()
                    .uri("/collections/{collection}", collectionName).retrieve().toBodilessEntity());
            collectionReady = true;
            return;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() != 404) throw failure;
        }
        // 命名稠密向量 dense + 稀疏向量 sparse，支持原生混合检索
        Map<String, Object> body = Map.of(
                "vectors", Map.of(DENSE_NAME, Map.of(
                        "size", dimension,
                        "distance", "Cosine")),
                "sparse_vectors", Map.of(SPARSE_NAME, Map.of()));
        try {
            transport("create_collection", () -> restClient.put()
                    .uri("/collections/{collection}", collectionName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) {
                throw e;
            }
        }
        collectionReady = true;
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
