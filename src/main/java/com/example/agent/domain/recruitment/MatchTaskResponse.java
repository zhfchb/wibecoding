package com.example.agent.domain.recruitment;

import java.time.LocalDateTime;

public record MatchTaskResponse(String id, Long positionId, String requestKey, String status, String phase,
                                int completedBatches, int totalBatches, int candidateCount,
                                int degradedCount, String errorCode, String errorMessage,
                                LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deadlineAt) {
}
