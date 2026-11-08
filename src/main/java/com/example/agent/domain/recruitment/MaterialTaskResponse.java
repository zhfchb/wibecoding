package com.example.agent.domain.recruitment;

import java.time.LocalDateTime;

public record MaterialTaskResponse(String id, Long resumeId, String kind, String requestKey, String status,
                                   String phase, String errorCode, String errorMessage,
                                   LocalDateTime deadlineAt, boolean stale, Long actorId, boolean canCancel) {
    public MaterialTaskResponse(String id, Long resumeId, String kind, String requestKey, String status,
                                   String phase, String errorCode, String errorMessage,
                                   LocalDateTime deadlineAt, boolean stale) { this(id, resumeId, kind, requestKey, status, phase, errorCode, errorMessage, deadlineAt, stale,null,false); }
    public MaterialTaskResponse authority(Long actorId, boolean canCancel) { return new MaterialTaskResponse(id, resumeId, kind, requestKey, status, phase, errorCode, errorMessage, deadlineAt, stale,actorId,canCancel); }
}
