package com.example.agent.config;

import com.example.agent.rag.DocumentChunker;
import com.example.agent.rag.InMemoryVectorStore;
import com.example.agent.rag.LexicalHashEmbeddingModel;
import com.example.agent.rag.QdrantVectorStore;
import com.example.agent.rag.VectorStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    @Bean
    public EmbeddingModel embeddingModel(RagProperties properties) {
        RagProperties.Embedding embedding = properties.embedding();
        if ("openai".equalsIgnoreCase(embedding.provider())) {
            return OpenAiEmbeddingModel.builder()
                    .baseUrl(embedding.baseUrl())
                    .apiKey(embedding.apiKey())
                    .modelName(embedding.modelName())
                    .dimensions(embedding.dimension())
                    .build();
        }
        return new LexicalHashEmbeddingModel(embedding.dimension());
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
    public DocumentChunker documentChunker(RagProperties properties) {
        RagProperties.Chunking chunking = properties.chunking();
        return new DocumentChunker(chunking.maxChars(), chunking.overlapChars());
    }
}
