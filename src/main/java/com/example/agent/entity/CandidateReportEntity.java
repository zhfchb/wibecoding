package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 候选人评估报告
 */
@Data
@TableName("candidate_report")
public class CandidateReportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resumeId;

    private Long positionId;

    /** 一句话总评 */
    private String summary;

    /** 优势 */
    private String strength;

    /** 风险提示 */
    private String risk;

    /** 建议薪资区间 */
    private String salarySuggest;

    /** 市场行情引用（来源 URL JSON） */
    private String marketRef;

    /** STRONG_YES/YES/NO */
    private String finalRecommend;

    /** 完整报告 Markdown */
    private String reportMd;

    /** PENDING/APPROVED/REJECTED */
    private String hrDecision;

    private LocalDateTime createdAt;
}
