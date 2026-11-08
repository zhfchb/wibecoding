package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.config.RagProperties;
import com.example.agent.domain.recruitment.CandidateReportResponse;
import com.example.agent.entity.CandidateReportEntity;
import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.entity.MatchResultEntity;
import com.example.agent.mapper.CandidateReportMapper;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.service.AiGateway;
import com.example.agent.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 候选人评估报告（M4）。
 * <p>数据链路：匹配评分 + 简历画像 → Tavily 搜索岗位市场行情 → LLM 整合生成
 * 结构化结论（总评/优势/风险/建议薪资/最终推荐）+ 完整 Markdown 报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {
    private final com.example.agent.service.company.PositionAccess positionAccess;

    /** 送入 LLM 的简历原文最大长度 */
    private static final int MAX_RESUME_TEXT = 3000;

    /** 市场行情引用最多保留条数 */
    private static final int MAX_MARKET_REFS = 5;

    private final CandidateResumeMapper candidateResumeMapper;
    private final CandidateReportMapper candidateReportMapper;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    private final org.springframework.beans.factory.ObjectProvider<com.example.agent.service.materials.MaterialTaskService> materialTasks;
    private final com.example.agent.service.materials.MaterialTaskStore materialStore;

    @Override
    public CandidateReportResponse generate(Long resumeId) {
        materialTasks.getObject().runSync(resumeId,"REPORT");
        return getReport(resumeId);
    }

    @Override
    public CandidateReportEntity prepare(JobPositionEntity position,CandidateResumeEntity resume,MatchResultEntity match,Runnable beforeGenerate) throws Exception {
        var refs=searchMarket(position.getTitle(),position.getCity());
        beforeGenerate.run();
        var report=callLlm(position,resume,match,refs);
        report.setMarketRef(toMarketRefJson(refs));
        return report;
    }

    @Override
    public CandidateReportResponse getReport(Long resumeId) {
        CandidateResumeEntity resume = positionAccess.resume(resumeId);
        CandidateReportEntity report = candidateReportMapper.selectOne(
                new LambdaQueryWrapper<CandidateReportEntity>()
                        .eq(CandidateReportEntity::getResumeId, resumeId)
                        .orderByDesc(CandidateReportEntity::getId)
                        .last("LIMIT 1"));
        if (report == null) {
            throw new IllegalArgumentException("该候选人尚未生成评估报告");
        }
        return toResponse(report, resume);
    }

    @Override
    public List<CandidateReportResponse> listReports(Long positionId) {
        positionAccess.readable(positionId);
        List<CandidateReportEntity> reports = candidateReportMapper.selectList(
                new LambdaQueryWrapper<CandidateReportEntity>()
                        .eq(CandidateReportEntity::getPositionId, positionId));
        if (reports.isEmpty()) {
            return List.of();
        }
        // 展示生成时的来源分数；没有来源记录的历史报告排在最后。
        List<CandidateReportResponse> result = new ArrayList<>();
        for (CandidateReportEntity report : reports) {
            CandidateResumeEntity resume = candidateResumeMapper.selectById(report.getResumeId());
            result.add(toResponse(report, resume));
        }
        result.sort(java.util.Comparator.comparing(CandidateReportResponse::matchScore,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return result;
    }

    // ==================== LLM 报告生成 ====================

    private CandidateReportEntity callLlm(JobPositionEntity position,
                                          CandidateResumeEntity resume,
                                          MatchResultEntity match,
                                          List<CandidateReportResponse.MarketRef> marketRefs) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深技术招聘负责人，基于以下信息输出候选人评估报告。\n\n");
        sb.append("【岗位】").append(position.getTitle())
                .append("（").append(position.getCity() == null ? "城市不限" : position.getCity()).append("）\n");
        sb.append("【岗位薪资预算】").append(position.getSalaryMin() == null ? "-" : position.getSalaryMin())
                .append("-").append(position.getSalaryMax() == null ? "-" : position.getSalaryMax()).append("k\n");
        sb.append("【必须技能】").append(position.getRequiredSkills()).append('\n');
        sb.append("【候选人】").append(StringUtils.hasText(resume.getCandidateName())
                ? resume.getCandidateName() : "未知")
                .append("，").append(resume.getYearsExp() == null ? 0 : resume.getYearsExp())
                .append("年经验，学历 ").append(StringUtils.hasText(resume.getEducation())
                        ? resume.getEducation() : "未知")
                .append("，上家 ").append(StringUtils.hasText(resume.getLastCompany())
                        ? resume.getLastCompany() : "未知").append('\n');
        sb.append("【候选人技能】").append(StringUtils.hasText(resume.getSkills())
                ? resume.getSkills() : "未知").append('\n');
        sb.append("【匹配评分】综合 ").append(match.getMatchScore())
                .append(" 分（规则分 ").append(match.getScoreDetail() == null ? "-" : match.getScoreDetail())
                .append("），命中 ").append(match.getSkillHits())
                .append("，待核实技能 ").append(StringUtils.hasText(match.getSkillGaps())
                        ? match.getSkillGaps() : "无").append('\n');
        sb.append("简历未提及的技能仅表示待核实，不能断言候选人不具备。评分明细中的 DEGRADED 表示语义评分失败，")
                .append("RULE_ONLY 表示未设置必须技能；这两种情况的综合分仅使用规则分，不能描述为已完成模型评估。\n");
        String rawText = resume.getRawText();
        if (StringUtils.hasText(rawText)) {
            String brief = rawText.length() > MAX_RESUME_TEXT ? rawText.substring(0, MAX_RESUME_TEXT) : rawText;
            sb.append("\n【简历原文（截断）】\n").append(brief).append('\n');
        }
        if (!marketRefs.isEmpty()) {
            sb.append("\n【市场行情搜索结果（供参考，报告需引用来源）】\n");
            for (CandidateReportResponse.MarketRef ref : marketRefs) {
                sb.append("- ").append(ref.title()).append(" → ").append(ref.url()).append('\n');
            }
        }
        sb.append("""

                生成评估报告。只返回一个 JSON 对象，不要任何解释文字、不要 markdown 代码块，格式：
                {"summary":"一句话总评(50字内)","strength":"优势(150字内,分号分隔)","risk":"风险提示(100字内)","salarySuggest":"建议薪资区间,如 28-35k","finalRecommend":"STRONG_YES","reportMd":"完整报告Markdown(600-1000字,含技能画像/评分解读/优势风险/薪资建议/市场行情引用等章节)"}
                规则：finalRecommend 只能取 STRONG_YES/YES/NO（综合分≥85 且无关键缺失 → STRONG_YES；≥60 → YES；否则 NO）；薪资建议结合岗位预算、市场行情与候选人水平给出；reportMd 中引用市场行情时附来源链接。
                """);

        String raw = aiGateway.structured("report", sb.toString());
        String json = raw == null ? "" : raw.trim();
        int left = json.indexOf('{');
        int right = json.lastIndexOf('}');
        if (left < 0 || right <= left) {
            throw new IllegalArgumentException("LLM 未返回报告 JSON 对象");
        }
        JsonNode node = objectMapper.readTree(json.substring(left, right + 1));

        CandidateReportEntity report = new CandidateReportEntity();
        report.setSummary(textOrNull(node, "summary"));
        report.setStrength(textOrNull(node, "strength"));
        report.setRisk(textOrNull(node, "risk"));
        report.setSalarySuggest(textOrNull(node, "salarySuggest"));
        report.setFinalRecommend(textOrNull(node,"finalRecommend"));
        report.setReportMd(textOrNull(node, "reportMd"));
        if (!StringUtils.hasText(report.getSummary()) || !StringUtils.hasText(report.getReportMd())) {
            throw new IllegalArgumentException("LLM 报告内容不完整");
        }
        return report;
    }

    // ==================== 市场行情搜索 ====================

    /**
     * 直连 Tavily 搜索岗位市场行情（薪资水平/人才供需），返回标题+URL 引用列表。
     * 搜索未启用或失败时返回空列表，不阻断报告生成。
     */
    private List<CandidateReportResponse.MarketRef> searchMarket(String title, String city) {
        var config = ragProperties.webSearch();
        if (config == null || !config.enabled() || !StringUtils.hasText(title)) return List.of();
        try {
            var result = aiGateway.post("/internal/v1/search", java.util.Map.of("message",
                (city == null ? "" : city + " ") + title + " 薪资 行情"));
            List<CandidateReportResponse.MarketRef> refs = new ArrayList<>();
            for (var item : result.path("results")) {
                String url = item.path("url").asText();
                if ((url.startsWith("https://") || url.startsWith("http://")) && refs.size() < MAX_MARKET_REFS) {
                    refs.add(new CandidateReportResponse.MarketRef(item.path("title").asText(url), url));
                }
            }
            return refs;
        } catch (Exception e) { return List.of(); }
    }

    private String toMarketRefJson(List<CandidateReportResponse.MarketRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        try {
            ArrayNode array = objectMapper.createArrayNode();
            for (CandidateReportResponse.MarketRef ref : refs) {
                ObjectNode node = array.addObject();
                node.put("title", ref.title());
                node.put("url", ref.url());
            }
            return objectMapper.writeValueAsString(array);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 工具方法 ====================

    private CandidateReportResponse toResponse(CandidateReportEntity report,
                                               CandidateResumeEntity resume) {
        List<CandidateReportResponse.MarketRef> refs = new ArrayList<>();
        if (StringUtils.hasText(report.getMarketRef())) {
            try {
                JsonNode array = objectMapper.readTree(report.getMarketRef());
                if (array.isArray()) {
                    for (JsonNode item : array) {
                        refs.add(new CandidateReportResponse.MarketRef(
                                item.path("title").asText(), item.path("url").asText()));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return new CandidateReportResponse(
                report.getId(),
                report.getResumeId(),
                report.getPositionId(),
                resume == null ? null : resume.getCandidateName(),
                materialStore.reportScore(report.getId()),
                report.getSummary(),
                report.getStrength(),
                report.getRisk(),
                report.getSalarySuggest(),
                refs,
                report.getFinalRecommend(),
                report.getReportMd(),
                report.getHrDecision(),
                report.getCreatedAt() == null ? null : report.getCreatedAt().toString());
    }

    private String textOrNull(JsonNode node, String field) {
        if (node.hasNonNull(field) && !node.get(field).isTextual())
            throw new com.example.agent.service.materials.MaterialFailure("INVALID_OUTPUT","报告字段格式不符合要求，原内容保留");
        String value = node.path(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

}
