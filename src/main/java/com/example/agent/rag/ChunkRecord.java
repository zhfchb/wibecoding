package com.example.agent.rag;

/**
 * 写入向量库的一个文档切片。
 *
 * @param id           切片唯一 ID
 * @param documentId   所属文档 ID
 * @param text         切片正文
 * @param fileName     来源文件名（用于来源归因与精排）
 * @param chunkIndex   切片在文档中的序号（从 0 开始）
 * @param vector       稠密向量（语义检索）
 * @param sparseVector 稀疏向量（关键词检索），为空表示无稀疏表示
 * @param userId       所属用户 ID，null 表示全局共享
 */
public record ChunkRecord(
        String id,
        String documentId,
        String text,
        String fileName,
        int chunkIndex,
        float[] vector,
        SparseEmbedding sparseVector,
        Long userId) {
}
