package com.example.agent.rag;

import java.util.List;

/**
 * 不调用外部模型的兜底实现：跳过精排，按召回顺序截断。
 * 用于 rerank 关闭或 dev 环境无 API Key 的场景。
 */
public class NoopRerankModel implements RerankModel {

    @Override
    public List<VectorMatch> rerank(String query, List<VectorMatch> candidates, int topK) {
        if (candidates.size() <= topK) {
            return candidates;
        }
        return candidates.subList(0, topK);
    }
}
