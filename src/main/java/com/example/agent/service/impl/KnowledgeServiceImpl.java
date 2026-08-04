package com.example.agent.service.impl;

import com.example.agent.config.RagProperties;
import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.exception.UnsupportedDocumentTypeException;
import com.example.agent.rag.ChunkRecord;
import com.example.agent.rag.DocumentChunker;
import com.example.agent.rag.VectorStore;
import com.example.agent.service.KnowledgeService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final DocumentChunker documentChunker;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;

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
            vectorStore.add(new ChunkRecord(
                    UUID.randomUUID().toString(),
                    documentId,
                    segments.get(i).text(),
                    embeddings.get(i).vector()));
        }
        return new KnowledgeUploadResponse(documentId, fileName, chunks.size());
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
