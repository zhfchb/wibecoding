package com.example.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 硅基流动 Rerank 实现：调用 BGE-Reranker-v2-M3 对候选重排序。
 * 接口：POST {base-url}/rerank，与 Embedding 共用 baseUrl/apiKey。
 * 失败时降级为按召回顺序截断，不阻断主流程。
 */
@Slf4j
public class SiliconFlowRerankModel implements RerankModel {

    private final String modelName;
    private final RestClient restClient;

    public SiliconFlowRerankModel(String baseUrl, String apiKey, String modelName) {
        this.modelName = modelName;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public List<VectorMatch> rerank(String query, List<VectorMatch> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        List<String> documents = new ArrayList<>(candidates.size());
        for (VectorMatch match : candidates) {
            documents.add(match.text());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", Math.min(topK, documents.size()));
        body.put("return_documents", false);

        try {
            JsonNode root = restClient.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            List<VectorMatch> ranked = new ArrayList<>();
            if (root != null && root.has("results")) {
                for (JsonNode item : root.path("results")) {
                    int index = item.path("index").asInt();
                    double score = item.path("relevance_score").asDouble();
                    VectorMatch origin = candidates.get(index);
                    ranked.add(new VectorMatch(
                            origin.id(),
                            origin.documentId(),
                            origin.text(),
                            origin.fileName(),
                            origin.chunkIndex(),
                            score));
                }
            }
            if (ranked.isEmpty()) {
                log.warn("rerank 返回空结果，降级为召回顺序截断");
                return fallback(candidates, topK);
            }
            return ranked.size() <= topK ? ranked : ranked.subList(0, topK);
        } catch (Exception e) {
            log.error("rerank 调用失败，降级为召回顺序截断 query={}", query, e);
            return fallback(candidates, topK);
        }
    }

    private List<VectorMatch> fallback(List<VectorMatch> candidates, int topK) {
        return candidates.size() <= topK ? candidates : candidates.subList(0, topK);
    }
}
