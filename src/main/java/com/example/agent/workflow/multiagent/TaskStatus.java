package com.example.agent.workflow.multiagent;

/**
 * 多 Agent 任务状态（M9）。
 *
 * <p>状态流转：
 * <pre>
 * RUNNING → AWAITING_REVIEW ──approve──→ APPROVED → COMPLETED
 *                          ──reject───→ REJECTED → RUNNING（重新生成）→ AWAITING_REVIEW ...
 *                          ──reject(maxRejects)──→ COMPLETED（强制通过）
 * </pre>
 */
public enum TaskStatus {
    /** 任务已提交，图正在执行（尚未到 review 节点） */
    RUNNING,
    /** 图执行到 review_node 前 interrupt，等待人工审批 */
    AWAITING_REVIEW,
    /** 人工审批通过 */
    APPROVED,
    /** 人工审批拒绝，将重新生成 */
    REJECTED,
    /** 任务已完成，最终答案已产出 */
    COMPLETED,
    /** 任务失败 */
    FAILED
}
