package com.example.agent.domain.recruitment;

import java.math.BigDecimal;
import java.util.List;

/**
 * 匹配结果视图（含候选人信息与评分明细）
 */
public record MatchResultResponse(
        Long id,
        Long resumeId,
        String candidateName,
        Integer yearsExp,
        String education,
        String lastCompany,
        BigDecimal matchScore,
        Integer rankNo,
        List<String> skillHits,
        List<String> skillGaps,
        ScoreDetail scoreDetail,
        String hrDecision) {

    /** 评分明细：各分项得分与权重（可解释性） */
    public record ScoreDetail(
            int ruleScore,
            Integer llmScore,
            int skillScore,
            int preferredScore,
            int yearsScore,
            int educationScore,
            String llmNotes,
            String scoringStatus) {
    }
}
