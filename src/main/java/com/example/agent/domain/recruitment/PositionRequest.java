package com.example.agent.domain.recruitment;

import lombok.Data;

import java.util.List;

/**
 * 创建/更新岗位请求
 */
@Data
public class PositionRequest {

    /** 岗位名称（必填） */
    private String title;

    private String department;

    /** Formal company department; null keeps the existing association on update. */
    private Long departmentId;

    private String city;

    /** 薪资下限(k) */
    private Integer salaryMin;

    /** 薪资上限(k) */
    private Integer salaryMax;

    /** 必须技能 */
    private List<String> requiredSkills;

    /** 加分技能 */
    private List<String> preferredSkills;

    /** 最低工作年限 */
    private Integer minYears;

    /** 学历要求 */
    private String education;

    /** JD 全文 */
    private String description;
}
