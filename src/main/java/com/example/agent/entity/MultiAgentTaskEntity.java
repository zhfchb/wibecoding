package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多 Agent 任务持久化实体（M9）。
 */
@Data
@TableName("multi_agent_task")
public class MultiAgentTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private String threadId;

    private String question;

    private String status;

    private String draft;

    private String finalAnswer;

    private String sourcesJson;

    private Integer rejectCount;

    private String reviewFeedback;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
