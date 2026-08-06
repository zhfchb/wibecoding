package com.example.agent.service;

import com.example.agent.config.RagProperties;
import com.example.agent.entity.DocumentEntity;
import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.exception.UnsupportedDocumentTypeException;
import com.example.agent.mapper.DocumentChunkMapper;
import com.example.agent.mapper.DocumentMapper;
import com.example.agent.rag.DocumentChunker;
import com.example.agent.rag.HashingSparseModel;
import com.example.agent.rag.InMemoryVectorStore;
import com.example.agent.rag.LexicalHashEmbeddingModel;
import com.example.agent.rag.VectorStore;
import com.example.agent.service.impl.KnowledgeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KnowledgeServiceTest {

    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final DocumentChunkMapper documentChunkMapper = mock(DocumentChunkMapper.class);
    private final VectorStore vectorStore = new InMemoryVectorStore();
    private final KnowledgeServiceImpl service = new KnowledgeServiceImpl(
            new DocumentChunker(50, 10),
            new LexicalHashEmbeddingModel(768),
            new HashingSparseModel(),
            vectorStore,
            properties(),
            documentMapper,
            documentChunkMapper,
            new ObjectMapper());

    @Test
    void uploadsTextDocumentAndReturnsChunkCount() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "spring-boot.md",
                "text/markdown",
                "Spring Boot 是 Java 后端框架。RAG 使用知识库检索增强回答。".getBytes(StandardCharsets.UTF_8));

        KnowledgeUploadResponse response = service.upload(file);

        assertThat(response.documentId()).isNotBlank();
        assertThat(response.fileName()).isEqualTo("spring-boot.md");
        assertThat(response.chunkCount()).isGreaterThan(0);
        verify(documentMapper).insert(any(DocumentEntity.class));
    }

    @Test
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report.pdf",
                "application/pdf",
                "fake pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(UnsupportedDocumentTypeException.class);
    }

    private RagProperties properties() {
        return new RagProperties(
                new RagProperties.Chunking(50, 10),
                new RagProperties.Retrieval(3, 0),
                new RagProperties.VectorStore("memory", "http://localhost:6333", "knowledge"),
                new RagProperties.Embedding("hash", 768, "text-embedding-3-small", "http://localhost", ""),
                new RagProperties.Upload(1024, List.of("txt", "md", "markdown")),
                new RagProperties.Hybrid(false, 20, 60),
                new RagProperties.Rerank(false, "noop", "", "", "", 0),
                new RagProperties.Adaptive(false, 1, 0.35),
                new RagProperties.MultiAgent(false, 2));
    }
}
