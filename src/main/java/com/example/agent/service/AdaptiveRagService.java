package com.example.agent.service;

import com.example.agent.domain.chat.RagChatResponse;

/**
 * 自适应 RAG 服务（M8）：基于 LangGraph4j 状态图实现
 * 「查询重写 → 检索 → 质量评分 → 重试/生成」的自适应循环。
 *
 * <p>与 {@link RagChatService} 的区别：
 * <ul>
 *   <li>普通 RAG：单次检索 → 直接生成</li>
 *   <li>自适应 RAG：检索后评估 top1 分数，不足则重写查询重试，重试耗尽走兜底回答</li>
 * </ul>
 */
public interface AdaptiveRagService {

    /**
     * 自适应 RAG 对话。
     * @param message 用户消息
     * @param sessionId 会话 ID（当前实现不跨轮记忆，仅用于响应回传）
     * @return 包含最终回答、来源切片与会话 ID 的响应
     */
    RagChatResponse chat(String message, String sessionId);
}
