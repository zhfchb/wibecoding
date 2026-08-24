package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人岗匹配结果
 */
@Data
@TableName("match_result")
public class MatchResultEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long positionId;

    private Long resumeId;

    /** 综合匹配分 0-100 */
    private BigDecimal matchScore;

    /** 命中技能 JSON */
    private String skillHits;

    /** 缺失技能 JSON */
    private String skillGaps;

    /** 评分明细 JSON（经验/技能/学历分项） */
    private String scoreDetail;

    /** PENDING/APPROVED/REJECTED */
    private String hrDecision;

    /** 排名 */
    private Integer rankNo;

    /** 产生该结果的 Agent 任务 ID */
    private Long taskId;

    private LocalDateTime createdAt;
}
