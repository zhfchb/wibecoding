package com.example.agent.domain.chat;

/**
 * RAG 回答引用的知识来源。
 *
 * @param text       命中的切片正文
 * @param fileName   来源文件名
 * @param chunkIndex 切片序号
 * @param score      相关性分数
 */
public record RagSource(String text, String fileName, int chunkIndex, double score) {
}
