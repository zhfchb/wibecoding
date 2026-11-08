package com.example.agent.domain.recruitment;

import java.time.LocalDateTime;

public record MatchTaskResponse(String id, Long positionId, String requestKey, String status, String phase,
                                int completedBatches, int totalBatches, int candidateCount,
                                int degradedCount, String errorCode, String errorMessage,
                                LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deadlineAt, Long actorId, boolean canCancel) {
    public MatchTaskResponse(String id, Long positionId, String requestKey, String status, String phase,
                                int completedBatches, int totalBatches, int candidateCount,
                                int degradedCount, String errorCode, String errorMessage,
                                LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deadlineAt) { this(id, positionId, requestKey, status, phase, completedBatches, totalBatches, candidateCount, degradedCount, errorCode, errorMessage, createdAt, updatedAt, deadlineAt,null,false); }
    public MatchTaskResponse authority(Long actorId, boolean canCancel) { return new MatchTaskResponse(id, positionId, requestKey, status, phase, completedBatches, totalBatches, candidateCount, degradedCount, errorCode, errorMessage, createdAt, updatedAt, deadlineAt,actorId,canCancel); }

}
