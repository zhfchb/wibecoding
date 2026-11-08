package com.example.agent.domain.recruitment;

import java.time.LocalDateTime;
import java.util.List;

public record ResumeParseTaskResponse(String id, Long positionId, String requestKey, String status,
                                      int total, int succeeded, int failed, int cancelled,
                                      String errorCode, String errorMessage, LocalDateTime deadlineAt,
                                      List<Item> items, Long actorId, boolean canCancel) {
    public ResumeParseTaskResponse(String id, Long positionId, String requestKey, String status,
                                      int total, int succeeded, int failed, int cancelled,
                                      String errorCode, String errorMessage, LocalDateTime deadlineAt,
                                      List<Item> items) { this(id, positionId, requestKey, status, total, succeeded, failed, cancelled, errorCode, errorMessage, deadlineAt, items,null,false); }
    public ResumeParseTaskResponse authority(Long actorId, boolean canCancel) { return new ResumeParseTaskResponse(id, positionId, requestKey, status, total, succeeded, failed, cancelled, errorCode, errorMessage, deadlineAt, items,actorId,canCancel); }

    public record Item(Long resumeId, String candidateName, String status, String errorCode, String errorMessage) {}
}
