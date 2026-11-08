package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 招聘岗位
 */
@Data
@TableName("job_position")
public class JobPositionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布 HR 用户 ID */
    private Long userId;

    /** 岗位名称 */
    private String title;

    /** 部门 */
    private String department;

    private Long companyId;

    private Long departmentId;

    /** 工作城市 */
    private String city;

    /** 薪资下限(k) */
    private Integer salaryMin;

    /** 薪资上限(k) */
    private Integer salaryMax;

    /** 必须技能 JSON 数组 */
    private String requiredSkills;

    /** 加分技能 JSON 数组 */
    private String preferredSkills;

    /** 最低工作年限 */
    private Integer minYears;

    /** 学历要求 */
    private String education;

    /** JD 全文 */
    private String description;

    /** 关联上传的 JD 文档 ID（document.document_id） */
    private String docId;

    /** DRAFT/OPEN/MATCHING/CLOSED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
