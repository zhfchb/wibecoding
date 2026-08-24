package com.example.agent.domain.recruitment;

/**
 * 面试题响应视图
 */
public record InterviewQuestionResponse(
        Long id,
        Long resumeId,
        String candidateName,
        String category,
        String question,
        String assessPoint,
        String referenceAnswer,
        String difficulty,
        String createdAt) {
}
