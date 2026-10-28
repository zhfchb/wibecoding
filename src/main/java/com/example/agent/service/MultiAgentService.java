package com.example.agent.service;

import com.example.agent.workflow.multiagent.MultiAgentTask;

import java.util.List;

/**
 * 多 Agent 协作服务（M9）。
 *
 * <p>基于 Python LangGraph 状态图编排「检索 Agent → 生成 Agent → HITL 审核」工作流。
 * 由于 HITL 需要人工介入，接口采用异步任务模式：提交→轮询→审批。
 */
public interface MultiAgentService {

    /**
     * 提交多 Agent 任务。
     * @param question 用户问题
     * @return 任务快照（初始状态为 RUNNING，图执行到 review 前会变为 AWAITING_REVIEW）
     */
    MultiAgentTask submit(String question);

    /**
     * 查询任务状态。
     * @param taskId 提交时返回的任务 ID
     * @return 任务快照，包含当前状态/草稿/最终答案等
     */
    MultiAgentTask getTask(String taskId);

    /**
     * 列出待审核任务（进程重启后可从此接口恢复 AWAITING_REVIEW 任务列表）。
     * @return 待审核任务列表，按创建时间升序
     */
    List<MultiAgentTask> listPendingTasks();

    /**
     * 人工审批：通过。
     * @param taskId 任务 ID
     * @return 审批后的任务快照（状态应为 COMPLETED）
     */
    MultiAgentTask approve(String taskId);

    /**
     * 人工审批：拒绝，携带反馈，触发重新生成。
     * @param taskId 任务 ID
     * @param feedback 拒绝原因/修改建议，供生成 Agent 下轮参考
     * @return 审批后的任务快照（状态应为 AWAITING_REVIEW，除非达到 maxRejects 终止失败）
     */
    MultiAgentTask reject(String taskId, String feedback);
}
