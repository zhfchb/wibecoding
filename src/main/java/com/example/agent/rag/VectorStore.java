package com.example.agent.rag;

import java.util.List;

public interface VectorStore {

    void add(ChunkRecord chunk);

    List<VectorMatch> search(float[] queryVector, int topK, double minScore);

    void deleteByDocumentId(String documentId);
}
