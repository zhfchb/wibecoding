package com.example.agent.rag;

import java.io.Serializable;

/**
 * 检索命中的切片结果。
 *
 * @param id         切片 ID
 * @param documentId 所属文档 ID
 * @param text       切片正文
 * @param fileName   来源文件名
 * @param chunkIndex 切片序号
 * @param score      相关性分数（混合检索为 RRF 融合分；精排后为 rerank 分）
 */
public record VectorMatch(
        String id,
        String documentId,
        String text,
        String fileName,
        int chunkIndex,
        double score) implements Serializable {
}
