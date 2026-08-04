package com.example.agent.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class InMemoryVectorStore implements VectorStore {

    private final List<ChunkRecord> chunks = Collections.synchronizedList(new ArrayList<>());

    @Override
    public synchronized void add(ChunkRecord chunk) {
        chunks.add(chunk);
    }

    @Override
    public synchronized List<VectorMatch> search(float[] queryVector, int topK, double minScore) {
        return chunks.stream()
                .map(chunk -> new VectorMatch(
                        chunk.id(),
                        chunk.documentId(),
                        chunk.text(),
                        cosine(queryVector, chunk.vector())))
                .filter(match -> match.score() >= minScore)
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public synchronized void deleteByDocumentId(String documentId) {
        List<ChunkRecord> remaining = new ArrayList<>();
        for (ChunkRecord chunk : chunks) {
            if (!chunk.documentId().equals(documentId)) {
                remaining.add(chunk);
            }
        }
        chunks.clear();
        chunks.addAll(remaining);
    }

    private double cosine(float[] left, float[] right) {
        int size = Math.min(left.length, right.length);
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
