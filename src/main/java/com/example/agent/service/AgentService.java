package com.example.agent.service;

public interface AgentService {

    /**
     * Agent 对话：LLM 自动决策是否调用工具（时间查询、计算器等），多轮工具调用后给出最终答案。
     */
    String chat(String message);
}
