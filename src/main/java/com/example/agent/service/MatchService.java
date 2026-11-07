package com.example.agent.service;

import com.example.agent.domain.recruitment.MatchResultResponse;

import java.util.List;

/**
 * 智能招聘：人岗匹配引擎（M2，规则70% + LLM语义30%）
 */
public interface MatchService {
    void runTask(com.example.agent.service.matching.MatchTaskStore.Task task);

    /**
     * 对岗位下全部已解析简历执行匹配：规则评分 + LLM 语义等价评分，加权排序后落库。
     *
     * @return 按匹配分降序的候选人列表
     */
    List<MatchResultResponse> runMatch(Long positionId);

    /**
     * 查询岗位最近一次匹配结果（按分数降序）。
     */
    List<MatchResultResponse> listMatches(Long positionId);

    /**
     * HR 对单条匹配结果做决策（通过/拒绝）。
     */
    void decide(Long matchId, boolean approved);
}
