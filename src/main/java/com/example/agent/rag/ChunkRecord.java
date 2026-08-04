package com.example.agent.rag;

public record ChunkRecord(String id, String documentId, String text, float[] vector) {
}
