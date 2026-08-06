package com.example.agent.rag;

import java.util.List;

/**
 * Rerank 精排模型：对混合检索召回的候选按查询相关度重排序并截断。
 */
public interface RerankModel {

    /**
     * @param query     用户查询
     * @param candidates 召回候选（顺序即为召回顺序）
     * @param topK      精排后保留数量
     * @return 按 rerank 分降序排列的结果（score 已替换为 rerank 分）
     */
    List<VectorMatch> rerank(String query, List<VectorMatch> candidates, int topK);
}
