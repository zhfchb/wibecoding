package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.agent.config.RagProperties;
import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.entity.DocumentChunkEntity;
import com.example.agent.entity.DocumentEntity;
import com.example.agent.exception.UnsupportedDocumentTypeException;
import com.example.agent.mapper.DocumentChunkMapper;
import com.example.agent.mapper.DocumentMapper;
import com.example.agent.rag.ChunkRecord;
import com.example.agent.rag.DocumentChunker;
import com.example.agent.rag.PdfTextExtractor;
import com.example.agent.rag.SparseEmbedding;
import com.example.agent.rag.SparseEmbeddingModel;
import com.example.agent.rag.VectorStore;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.KnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final DocumentChunkMapper documentChunkMapper;
    private final ObjectMapper objectMapper;

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

        String content = readContent(file, extension);
        List<String> chunks = documentChunker.chunk(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空，无法导入知识库");
        }

        String documentId = UUID.randomUUID().toString();
        Long userId = SecurityUtils.getCurrentUserId();
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
            String chunkId = UUID.randomUUID().toString();
            float[] denseVector = embeddings.get(i).vector();
            ChunkRecord chunk = new ChunkRecord(
                    chunkId,
                    documentId,
                    chunkText,
                    fileName,
                    i,
                    denseVector,
                    sparse,
                    userId);
            vectorStore.add(chunk);
            // 持久化 chunk 到 MySQL，进程重启后可恢复内存向量库
            persistChunk(chunk, sparse);
        }

        // 持久化文档元数据到 MySQL
        persistDocumentMetadata(documentId, fileName, chunks.size(), file.getSize(), userId);

        return new KnowledgeUploadResponse(documentId, fileName, chunks.size());
    }

    @Override
    public IPage<DocumentEntity> listDocuments(int page, int size, Long userId) {
        Page<DocumentEntity> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<DocumentEntity> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(DocumentEntity::getUserId, userId);
        }
        return documentMapper.selectPage(pageObj, wrapper);
    }

    @Override
    public void deleteDocument(String documentId, Long userId) {
        if (!StringUtils.hasText(documentId)) {
            throw new IllegalArgumentException("documentId 不能为空");
        }

        // 1. 查文档元数据，校验归属
        LambdaQueryWrapper<DocumentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentEntity::getDocumentId, documentId);
        if (userId != null) {
            wrapper.eq(DocumentEntity::getUserId, userId);
        }
        DocumentEntity doc = documentMapper.selectOne(wrapper);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在或无权删除");
        }

        // 2. 逻辑删除文档元数据（@TableLogic 自动设置 deleted=1）
        documentMapper.deleteById(doc.getId());

        // 3. 物理删除 chunk 持久化
        documentChunkMapper.deleteByDocumentId(documentId);

        // 4. 删除内存向量库中的向量
        vectorStore.deleteByDocumentId(documentId);

        log.info("文档已删除: documentId={} fileName={} chunks={}", documentId, doc.getFileName(), doc.getChunkCount());
    }

    private void persistDocumentMetadata(String documentId, String fileName, int chunkCount, long fileSize, Long userId) {
        try {
            DocumentEntity entity = new DocumentEntity();
            entity.setDocumentId(documentId);
            entity.setFileName(fileName);
            entity.setChunkCount(chunkCount);
            entity.setFileSize(fileSize);
            entity.setUserId(userId);
            entity.setCreatedAt(LocalDateTime.now());
            documentMapper.insert(entity);
        } catch (Exception e) {
            // 元数据持久化失败不影响向量库导入结果，只记日志
            log.error("保存文档元数据失败 documentId={} fileName={}", documentId, fileName, e);
        }
    }

    /**
     * 持久化单个 chunk 到 MySQL（含稠密 + 稀疏向量的 JSON 序列化）。
     */
    private void persistChunk(ChunkRecord chunk, SparseEmbedding sparse) {
        try {
            DocumentChunkEntity entity = new DocumentChunkEntity();
            entity.setChunkId(chunk.id());
            entity.setDocumentId(chunk.documentId());
            entity.setFileName(chunk.fileName());
            entity.setChunkIndex(chunk.chunkIndex());
            entity.setText(chunk.text());
            entity.setDenseVector(objectMapper.writeValueAsString(chunk.vector()));
            if (sparse != null && !sparse.isEmpty()) {
                entity.setSparseIndices(objectMapper.writeValueAsString(sparse.indices()));
                entity.setSparseValues(objectMapper.writeValueAsString(sparse.values()));
            }
            entity.setUserId(chunk.userId());
            entity.setCreatedAt(LocalDateTime.now());
            documentChunkMapper.insert(entity);
        } catch (Exception e) {
            log.error("保存 chunk 失败 chunkId={} documentId={}", chunk.id(), chunk.documentId(), e);
        }
    }

    private String extensionOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    /**
     * 根据扩展名选择解析方式：
     * <ul>
     *   <li>pdf：用 PDFBox 抽取文本</li>
     *   <li>txt/md/markdown：按 UTF-8 读取</li>
     * </ul>
     */
    private String readContent(MultipartFile file, String extension) {
        if ("pdf".equals(extension)) {
            try (java.io.InputStream in = file.getInputStream()) {
                String text = PdfTextExtractor.extract(in);
                if (!StringUtils.hasText(text)) {
                    throw new IllegalArgumentException(
                            "PDF 内容为空，可能是扫描版 PDF（无文字层），请使用带文字的 PDF");
                }
                return text;
            } catch (IOException e) {
                throw new IllegalArgumentException("读取 PDF 文件失败", e);
            }
        }
        // txt / md / markdown 等纯文本格式
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文档失败", e);
        }
    }
}
