package com.example.agent.domain.recruitment;

import java.time.LocalDateTime;

public record MaterialTaskResponse(String id, Long resumeId, String kind, String requestKey, String status,
                                   String phase, String errorCode, String errorMessage,
                                   LocalDateTime deadlineAt, boolean stale) {}
