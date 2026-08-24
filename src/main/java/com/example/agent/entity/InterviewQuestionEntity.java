package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定制面试题
 */
@Data
@TableName("interview_question")
public class InterviewQuestionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resumeId;

    private Long positionId;

    /** 技术/项目/行为 */
    private String category;

    /** 题目 */
    private String question;

    /** 考察点 */
    private String assessPoint;

    /** 参考答案要点 */
    private String referenceAnswer;

    /** EASY/MEDIUM/HARD */
    private String difficulty;

    /** PENDING/APPROVED/REJECTED */
    private String hrDecision;

    private LocalDateTime createdAt;
}
