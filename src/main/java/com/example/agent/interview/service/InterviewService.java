package com.example.agent.interview.service;

import com.example.agent.interview.dto.InterviewQuestionResponse;
import java.util.List;

/**
 * 智能招聘：LLM 定制面试题生成（M3）
 */
public interface InterviewService {
    List<com.example.agent.interview.entity.InterviewQuestionEntity> prepare(com.example.agent.recruitment.entity.JobPositionEntity position,
            com.example.agent.recruitment.entity.CandidateResumeEntity resume,com.example.agent.recruitment.entity.MatchResultEntity match) throws Exception;

    /**
     * 为候选人生成定制面试题：基于岗位要求 + 候选人技能/缺失项 + 工作年限，
     * 由 LLM 生成技术/项目/行为三类题目，含考察点、参考答案、难度。
     * 若该候选人在岗位匹配中已被淘汰(REJECTED)则拒绝生成。
     */
    List<InterviewQuestionResponse> generate(Long resumeId);

    /**
     * 查询候选人已生成的面试题。
     */
    List<InterviewQuestionResponse> listQuestions(Long resumeId);
}
