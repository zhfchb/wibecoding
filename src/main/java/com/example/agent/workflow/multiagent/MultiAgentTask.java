package com.example.agent.workflow.multiagent;

import com.example.agent.domain.chat.RagSource;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 多 Agent 任务快照（M9），用于 REST 接口返回任务状态。
 */
public record MultiAgentTask(
        String taskId,
        String threadId,
        String question,
        TaskStatus status,
        String draft,
        String finalAnswer,
        List<RagSource> sources,
        int rejectCount,
        String reviewFeedback,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
