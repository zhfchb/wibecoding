package com.example.agent.domain.recruitment;

import java.time.LocalDateTime;
import java.util.List;

public record ResumeParseTaskResponse(String id, Long positionId, String requestKey, String status,
                                      int total, int succeeded, int failed, int cancelled,
                                      String errorCode, String errorMessage, LocalDateTime deadlineAt,
                                      List<Item> items) {
    public record Item(Long resumeId, String candidateName, String status, String errorCode, String errorMessage) {}
}
