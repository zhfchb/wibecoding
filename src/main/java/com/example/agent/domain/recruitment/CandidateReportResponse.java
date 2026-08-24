package com.example.agent.domain.recruitment;

import java.math.BigDecimal;
import java.util.List;

/**
 * 候选人评估报告响应视图
 */
public record CandidateReportResponse(
        Long id,
        Long resumeId,
        Long positionId,
        String candidateName,
        BigDecimal matchScore,
        String summary,
        String strength,
        String risk,
        String salarySuggest,
        List<MarketRef> marketRefs,
        String finalRecommend,
        String reportMd,
        String hrDecision,
        String createdAt) {

    /** 市场行情引用（标题+URL） */
    public record MarketRef(String title, String url) {
    }
}
