package com.example.agent.rag;

import com.example.agent.entity.DocumentChunkEntity;
import com.example.agent.mapper.DocumentChunkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时从 MySQL document_chunk 表加载切片到内存向量库。
 * 解决 InMemoryVectorStore 进程重启后向量数据丢失的问题。
 * 只对 InMemoryVectorStore 生效（Qdrant 自身有持久化）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorStoreInitializer implements ApplicationRunner {

    private final VectorStore vectorStore;
    private final DocumentChunkMapper documentChunkMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!(vectorStore instanceof InMemoryVectorStore memoryStore)) {
            log.info("向量库非 InMemoryVectorStore（{}），跳过启动加载", vectorStore.getClass().getSimpleName());
            return;
        }

        List<DocumentChunkEntity> all = documentChunkMapper.selectList(null);
        if (all.isEmpty()) {
            log.info("MySQL 无持久化 chunk，跳过向量库加载");
            return;
        }

        List<ChunkRecord> records = new ArrayList<>();
        int failed = 0;
        for (DocumentChunkEntity entity : all) {
            try {
                ChunkRecord chunk = toChunkRecord(entity);
                if (chunk != null) {
                    records.add(chunk);
                }
            } catch (Exception e) {
                failed++;
                log.error("反序列化 chunk 失败 id={} chunkId={}", entity.getId(), entity.getChunkId(), e);
            }
        }

        memoryStore.addAll(records);
        log.info("从 MySQL 加载 {} 条 chunk 到内存向量库（失败 {} 条）", records.size(), failed);
    }

    private ChunkRecord toChunkRecord(DocumentChunkEntity entity) throws Exception {
        float[] dense = objectMapper.readValue(entity.getDenseVector(), float[].class);

        SparseEmbedding sparse = null;
        if (entity.getSparseIndices() != null && !entity.getSparseIndices().isBlank()) {
            int[] indices = objectMapper.readValue(entity.getSparseIndices(), int[].class);
            float[] values = objectMapper.readValue(entity.getSparseValues(), float[].class);
            if (indices.length > 0) {
                sparse = new SparseEmbedding(indices, values);
            }
        }

        return new ChunkRecord(
                entity.getChunkId(),
                entity.getDocumentId(),
                entity.getText(),
                entity.getFileName(),
                entity.getChunkIndex(),
                dense,
                sparse,
                entity.getUserId());
    }
}
