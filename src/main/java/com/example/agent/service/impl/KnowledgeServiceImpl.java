package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.agent.config.RagProperties;
import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.entity.DocumentEntity;
import com.example.agent.exception.UnsupportedDocumentTypeException;
import com.example.agent.mapper.DocumentMapper;
import com.example.agent.rag.ChunkRecord;
import com.example.agent.rag.DocumentChunker;
import com.example.agent.rag.SparseEmbedding;
import com.example.agent.rag.SparseEmbeddingModel;
import com.example.agent.rag.VectorStore;
import com.example.agent.service.KnowledgeService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final DocumentChunker documentChunker;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbeddingModel sparseEmbeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final DocumentMapper documentMapper;

    @Override
    public KnowledgeUploadResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文档");
        }

        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : "unnamed";
        String extension = extensionOf(fileName);
        Set<String> allowedExtensions = new HashSet<>();
        for (String value : ragProperties.upload().allowedExtensions()) {
            allowedExtensions.add(value.toLowerCase(Locale.ROOT));
        }
        if (!allowedExtensions.contains(extension)) {
            throw new UnsupportedDocumentTypeException("仅支持 " + allowedExtensions + " 格式文档");
        }
        if (file.getSize() > ragProperties.upload().maxFileSizeBytes()) {
            throw new IllegalArgumentException("文档大小不能超过 " + ragProperties.upload().maxFileSizeBytes() + " 字节");
        }

        String content = readUtf8(file);
        List<String> chunks = documentChunker.chunk(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空，无法导入知识库");
        }

        String documentId = UUID.randomUUID().toString();
        List<TextSegment> segments = chunks.stream()
                .map(text -> TextSegment.from(
                        text,
                        new Metadata()
                                .put("document_id", documentId)
                                .put("file_name", fileName)))
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        for (int i = 0; i < segments.size(); i++) {
            String chunkText = segments.get(i).text();
            SparseEmbedding sparse = sparseEmbeddingModel.embed(chunkText);
            vectorStore.add(new ChunkRecord(
                    UUID.randomUUID().toString(),
                    documentId,
                    chunkText,
                    fileName,
                    i,
                    embeddings.get(i).vector(),
                    sparse));
        }

        // 持久化文档元数据到 MySQL
        persistDocumentMetadata(documentId, fileName, chunks.size(), file.getSize());

        return new KnowledgeUploadResponse(documentId, fileName, chunks.size());
    }

    @Override
    public IPage<DocumentEntity> listDocuments(int page, int size) {
        Page<DocumentEntity> pageObj = new Page<>(page, size);
        return documentMapper.selectPage(pageObj, null);
    }

    private void persistDocumentMetadata(String documentId, String fileName, int chunkCount, long fileSize) {
        try {
            DocumentEntity entity = new DocumentEntity();
            entity.setDocumentId(documentId);
            entity.setFileName(fileName);
            entity.setChunkCount(chunkCount);
            entity.setFileSize(fileSize);
            entity.setCreatedAt(LocalDateTime.now());
            documentMapper.insert(entity);
        } catch (Exception e) {
            // 元数据持久化失败不影响向量库导入结果，只记日志
            log.error("保存文档元数据失败 documentId={} fileName={}", documentId, fileName, e);
        }
    }

    private String extensionOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    private String readUtf8(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文档失败", e);
        }
    }
}
