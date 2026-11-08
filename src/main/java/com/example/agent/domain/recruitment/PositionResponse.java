package com.example.agent.domain.recruitment;

import com.example.agent.entity.JobPositionEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 岗位响应视图（技能 JSON 字段转为数组）
 */
@JsonIgnoreProperties({"rawText"})
public record PositionResponse(
        Long id,
        String title,
        String department,
        String city,
        Integer salaryMin,
        Integer salaryMax,
        List<String> requiredSkills,
        List<String> preferredSkills,
        Integer minYears,
        String education,
        String description,
        String status,
        Integer resumeCount,
        Long companyId,
        Long departmentId,
        String departmentStatus) {

    public static PositionResponse from(JobPositionEntity entity, List<String> required,
                                        List<String> preferred, Integer resumeCount,
                                        com.example.agent.service.company.PositionDepartmentService.View department) {
        return new PositionResponse(
                entity.getId(),
                entity.getTitle(),
                department.name(),
                entity.getCity(),
                entity.getSalaryMin(),
                entity.getSalaryMax(),
                required,
                preferred,
                entity.getMinYears(),
                entity.getEducation(),
                entity.getDescription(),
                entity.getStatus(),
                resumeCount, entity.getCompanyId(), entity.getDepartmentId(), department.status());
    }
}
