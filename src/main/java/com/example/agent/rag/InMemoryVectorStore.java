package com.example.agent.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                        chunk.fileName(),
                        chunk.chunkIndex(),
                        cosine(queryVector, chunk.vector())))
                .filter(match -> match.score() >= minScore)
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public synchronized List<VectorMatch> search(float[] denseVector,
                                                 SparseEmbedding sparseVector,
                                                 int topK,
                                                 double minScore,
                                                 int rrfK) {
        if (sparseVector == null || sparseVector.isEmpty()) {
            return search(denseVector, topK, minScore);
        }

        // 1. 稠密路：计算 cosine，按分数排名
        List<Ranked> denseRanked = new ArrayList<>();
        for (ChunkRecord chunk : chunks) {
            double score = cosine(denseVector, chunk.vector());
            if (score >= minScore) {
                denseRanked.add(new Ranked(chunk, score));
            }
        }
        denseRanked.sort(Comparator.comparingDouble(Ranked::score).reversed());

        // 2. 稀疏路：计算稀疏点积（BM25 风格），按分数排名
        Map<Integer, Float> querySparse = toMap(sparseVector);
        List<Ranked> sparseRanked = new ArrayList<>();
        for (ChunkRecord chunk : chunks) {
            double score = sparseDot(querySparse, chunk.sparseVector());
            if (score > 0) {
                sparseRanked.add(new Ranked(chunk, score));
            }
        }
        sparseRanked.sort(Comparator.comparingDouble(Ranked::score).reversed());

        // 3. RRF 融合：score = Σ 1/(k + rank)
        Map<String, Double> fused = new HashMap<>();
        for (int rank = 0; rank < denseRanked.size(); rank++) {
            fused.merge(denseRanked.get(rank).chunk.id(), 1.0 / (rrfK + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < sparseRanked.size(); rank++) {
            fused.merge(sparseRanked.get(rank).chunk.id(), 1.0 / (rrfK + rank + 1), Double::sum);
        }

        return fused.entrySet().stream()
                .map(e -> {
                    ChunkRecord chunk = findById(e.getKey());
                    return new VectorMatch(
                            chunk.id(),
                            chunk.documentId(),
                            chunk.text(),
                            chunk.fileName(),
                            chunk.chunkIndex(),
                            e.getValue());
                })
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

    private ChunkRecord findById(String id) {
        for (ChunkRecord chunk : chunks) {
            if (chunk.id().equals(id)) {
                return chunk;
            }
        }
        throw new IllegalStateException("未找到切片 id=" + id);
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null) {
            return 0;
        }
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

    private Map<Integer, Float> toMap(SparseEmbedding sparse) {
        Map<Integer, Float> map = new HashMap<>();
        int[] indices = sparse.indices();
        float[] values = sparse.values();
        for (int i = 0; i < indices.length; i++) {
            map.merge(indices[i], values[i], Float::sum);
        }
        return map;
    }

    private double sparseDot(Map<Integer, Float> query, SparseEmbedding chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return 0;
        }
        double dot = 0;
        int[] indices = chunk.indices();
        float[] values = chunk.values();
        for (int i = 0; i < indices.length; i++) {
            Float q = query.get(indices[i]);
            if (q != null) {
                dot += q * values[i];
            }
        }
        return dot;
    }

    private record Ranked(ChunkRecord chunk, double score) {
    }
}
