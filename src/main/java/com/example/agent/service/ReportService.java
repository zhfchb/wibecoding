package com.example.agent.service;

import com.example.agent.domain.recruitment.CandidateReportResponse;

import java.util.List;

/**
 * 智能招聘：候选人评估报告（M4）
 */
public interface ReportService {
    com.example.agent.entity.CandidateReportEntity prepare(com.example.agent.entity.JobPositionEntity position,
            com.example.agent.entity.CandidateResumeEntity resume,com.example.agent.entity.MatchResultEntity match,
            Runnable beforeGenerate) throws Exception;

    /**
     * 为候选人生成评估报告：匹配评分 + 技能画像 + 市场行情(Web搜索) 由 LLM 整合成
     * 结构化结论（总评/优势/风险/建议薪资/最终推荐）+ 完整 Markdown 报告。
     * 前置条件：该候选人已完成匹配；已被 HR 淘汰的拒绝生成。
     */
    CandidateReportResponse generate(Long resumeId);

    /**
     * 查询候选人的评估报告（每人每岗位一份，取最新）。
     */
    CandidateReportResponse getReport(Long resumeId);

    /**
     * 查询岗位下全部候选人的评估报告（按匹配分降序）。
     */
    List<CandidateReportResponse> listReports(Long positionId);
}
