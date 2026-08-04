package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker(20, 5);

    @Test
    void keepsShortTextAsSingleChunk() {
        List<String> chunks = chunker.chunk("知识库测试");

        assertThat(chunks).containsExactly("知识库测试");
    }

    @Test
    void splitsLongTextIntoBoundedChunks() {
        String longText = "第一段介绍 Spring Boot。"
                + "第二段介绍 RAG 检索增强生成。"
                + "第三段介绍 Qdrant 向量数据库。";

        List<String> chunks = chunker.chunk(longText);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(20));
    }

    @Test
    void ignoresBlankDocument() {
        assertThat(chunker.chunk("  \n  ")).isEmpty();
    }
}
