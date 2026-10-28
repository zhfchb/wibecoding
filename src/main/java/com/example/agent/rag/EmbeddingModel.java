package com.example.agent.rag;

import java.util.List;

/** Application-owned vector contract, independent of any LLM framework. */
public interface EmbeddingModel {
    float[] embed(String text);
    default List<float[]> embedAll(List<String> texts) { return texts.stream().map(this::embed).toList(); }
    int dimension();
}
