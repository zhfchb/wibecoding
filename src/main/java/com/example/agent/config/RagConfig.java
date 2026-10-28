package com.example.agent.config;

import com.example.agent.rag.DocumentChunker;
import com.example.agent.rag.HashingSparseModel;
import com.example.agent.rag.InMemoryVectorStore;
import com.example.agent.rag.LexicalHashEmbeddingModel;
import com.example.agent.rag.NoopRerankModel;
import com.example.agent.rag.QdrantVectorStore;
import com.example.agent.rag.RerankModel;
import com.example.agent.rag.PythonRerankModel;
import com.example.agent.rag.PythonEmbeddingModel;
import com.example.agent.rag.EmbeddingModel;
import com.example.agent.service.AiGateway;
import com.example.agent.rag.SparseEmbeddingModel;
import com.example.agent.rag.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    @Bean
    public EmbeddingModel embeddingModel(RagProperties properties, AiGateway gateway) {
        RagProperties.Embedding embedding = properties.embedding();
        if ("openai".equalsIgnoreCase(embedding.provider())) {
            // BGE-M3 等模型原生输出固定维度，不传 dimensions 参数（传了会报 parameter invalid）
            // dimension 配置项仍保留，用于 Qdrant 集合 size 与 hash baseline
            return new PythonEmbeddingModel(gateway, embedding.dimension());
        }
        return new LexicalHashEmbeddingModel(embedding.dimension());
    }

    @Bean
    public SparseEmbeddingModel sparseEmbeddingModel() {
        // 客户端 TF-IDF 风格稀疏编码，dev/prod 一致，无需外部服务
        return new HashingSparseModel();
    }

    @Bean
    public VectorStore vectorStore(RagProperties properties) {
        RagProperties.VectorStore store = properties.vectorStore();
        if ("qdrant".equalsIgnoreCase(store.type())) {
            return new QdrantVectorStore(
                    store.qdrantUrl(),
                    store.collectionName(),
                    properties.embedding().dimension());
        }
        return new InMemoryVectorStore();
    }

    @Bean
    public RerankModel rerankModel(RagProperties properties, AiGateway gateway) {
        RagProperties.Rerank rerank = properties.rerank();
        if (rerank == null || !rerank.enabled()) {
            return new NoopRerankModel();
        }
        if ("siliconflow".equalsIgnoreCase(rerank.provider())) {
            return new PythonRerankModel(gateway);
        }
        return new NoopRerankModel();
    }

    @Bean
    public DocumentChunker documentChunker(RagProperties properties) {
        RagProperties.Chunking chunking = properties.chunking();
        return new DocumentChunker(chunking.maxChars(), chunking.overlapChars());
    }
}
