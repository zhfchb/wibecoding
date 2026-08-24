package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 候选人简历
 */
@Data
@TableName("candidate_resume")
public class CandidateResumeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应聘岗位 ID */
    private Long positionId;

    /** 上传 HR 用户 ID（冗余，权限过滤） */
    private Long userId;

    /** 候选人姓名 */
    private String candidateName;

    /** 联系方式（脱敏存储） */
    private String phone;

    private String email;

    /** 技能标签 JSON 数组 */
    private String skills;

    /** 工作年限 */
    private Integer yearsExp;

    /** 最高学历 */
    private String education;

    /** 最近公司 */
    private String lastCompany;

    /** 简历全文（供 LLM 分析） */
    private String rawText;

    /** 关联 document.document_id（复用解析/分块/向量化管道） */
    private String docId;

    /** PENDING/PARSED/FAILED */
    private String parseStatus;

    /** 解析失败原因 */
    private String parseError;

    private LocalDateTime createdAt;
}
