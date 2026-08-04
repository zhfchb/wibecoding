package com.example.agent.rag;

public record VectorMatch(String id, String documentId, String text, double score) {
}
