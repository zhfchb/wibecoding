package com.example.agent.domain.recruitment;

import com.example.agent.entity.CandidateResumeEntity;

import java.util.List;

/**
 * 简历响应视图（不含全文，技能转数组）
 */
public record ResumeResponse(
        Long id,
        Long positionId,
        String candidateName,
        String email,
        List<String> skills,
        Integer yearsExp,
        String education,
        String lastCompany,
        String fileName,
        String parseStatus,
        String parseError,
        String createdAt) {

    public static ResumeResponse from(CandidateResumeEntity entity, String fileName, List<String> skills) {
        return new ResumeResponse(
                entity.getId(),
                entity.getPositionId(),
                entity.getCandidateName(),
                entity.getEmail(),
                skills,
                entity.getYearsExp(),
                entity.getEducation(),
                entity.getLastCompany(),
                fileName,
                entity.getParseStatus(),
                entity.getParseError(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
    }
}
