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
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.mapper.MatchResultMapper;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 候选人评估报告（M4）。
 * <p>数据链路：匹配评分 + 简历画像 → Tavily 搜索岗位市场行情 → LLM 整合生成
 * 结构化结论（总评/优势/风险/建议薪资/最终推荐）+ 完整 Markdown 报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final String TAVILY_API = "https://api.tavily.com/search";

    /** 送入 LLM 的简历原文最大长度 */
    private static final int MAX_RESUME_TEXT = 3000;

    /** 市场行情引用最多保留条数 */
    private static final int MAX_MARKET_REFS = 5;

    private final JobPositionMapper jobPositionMapper;
    private final CandidateResumeMapper candidateResumeMapper;
    private final MatchResultMapper matchResultMapper;
    private final CandidateReportMapper candidateReportMapper;
    private final com.example.agent.service.AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    @Override
    @Transactional
    public CandidateReportResponse generate(Long resumeId) {
        CandidateResumeEntity resume = requireOwnedResume(resumeId);
        if (!"PARSED".equals(resume.getParseStatus())) {
            throw new IllegalArgumentException("简历尚未解析成功，无法生成报告");
        }
        JobPositionEntity position = jobPositionMapper.selectById(resume.getPositionId());

        // 报告以匹配结果为基础
        MatchResultEntity match = matchResultMapper.selectOne(
                new LambdaQueryWrapper<MatchResultEntity>()
                        .eq(MatchResultEntity::getResumeId, resumeId)
                        .orderByDesc(MatchResultEntity::getId)
                        .last("LIMIT 1"));
        if (match == null) {
            throw new IllegalArgumentException("该候选人尚未完成人岗匹配，请先执行匹配再生成报告");
        }
        if ("REJECTED".equals(match.getHrDecision())) {
            throw new IllegalArgumentException("该候选人已被 HR 淘汰，不再生成评估报告");
        }

        // Web 搜索市场行情（失败不阻断报告生成）
        List<CandidateReportResponse.MarketRef> marketRefs = searchMarket(position.getTitle(), position.getCity());

        CandidateReportEntity report;
        try {
            report = callLlm(position, resume, match, marketRefs);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LLM 报告生成失败: resumeId={}, {}", resumeId, e.getMessage());
            throw new IllegalArgumentException("评估报告生成失败，请稍后重试");
        }

        // 重新生成覆盖旧报告
        candidateReportMapper.delete(new LambdaQueryWrapper<CandidateReportEntity>()
                .eq(CandidateReportEntity::getResumeId, resumeId));
        report.setResumeId(resumeId);
        report.setPositionId(position.getId());
        report.setMarketRef(toMarketRefJson(marketRefs));
        report.setHrDecision("PENDING");
        report.setCreatedAt(LocalDateTime.now());
        candidateReportMapper.insert(report);
        log.info("评估报告生成完成: resumeId={} positionId={} recommend={} marketRefs={}",
                resumeId, position.getId(), report.getFinalRecommend(), marketRefs.size());
        return toResponse(report, resume, match);
    }

    @Override
    public CandidateReportResponse getReport(Long resumeId) {
        CandidateResumeEntity resume = requireOwnedResume(resumeId);
        MatchResultEntity match = matchResultMapper.selectOne(
                new LambdaQueryWrapper<MatchResultEntity>()
                        .eq(MatchResultEntity::getResumeId, resumeId)
                        .orderByDesc(MatchResultEntity::getId)
                        .last("LIMIT 1"));
        CandidateReportEntity report = candidateReportMapper.selectOne(
                new LambdaQueryWrapper<CandidateReportEntity>()
                        .eq(CandidateReportEntity::getResumeId, resumeId)
                        .orderByDesc(CandidateReportEntity::getId)
                        .last("LIMIT 1"));
        if (report == null) {
            throw new IllegalArgumentException("该候选人尚未生成评估报告");
        }
        return toResponse(report, resume, match);
    }

    @Override
    public List<CandidateReportResponse> listReports(Long positionId) {
        requireOwnedPosition(positionId);
        List<CandidateReportEntity> reports = candidateReportMapper.selectList(
                new LambdaQueryWrapper<CandidateReportEntity>()
                        .eq(CandidateReportEntity::getPositionId, positionId));
        if (reports.isEmpty()) {
            return List.of();
        }
        // 批量取简历与匹配分
        List<CandidateReportResponse> result = new ArrayList<>();
        for (CandidateReportEntity report : reports) {
            CandidateResumeEntity resume = candidateResumeMapper.selectById(report.getResumeId());
            MatchResultEntity match = matchResultMapper.selectOne(
                    new LambdaQueryWrapper<MatchResultEntity>()
                            .eq(MatchResultEntity::getResumeId, report.getResumeId())
                            .orderByDesc(MatchResultEntity::getId)
                            .last("LIMIT 1"));
            result.add(toResponse(report, resume, match));
        }
        result.sort((a, b) -> b.matchScore() == null || a.matchScore() == null ? 0
                : b.matchScore().compareTo(a.matchScore()));
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
                .append("，缺失 ").append(StringUtils.hasText(match.getSkillGaps())
                        ? match.getSkillGaps() : "无").append('\n');
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
        report.setFinalRecommend(normalizeRecommend(node.path("finalRecommend").asText("YES")));
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
        RagProperties.WebSearch config = ragProperties.webSearch();
        if (config == null || !config.enabled() || !StringUtils.hasText(config.apiKey())
                || !StringUtils.hasText(title)) {
            return List.of();
        }
        String query = (StringUtils.hasText(city) ? city + " " : "") + title + " 薪资 行情";
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("api_key", config.apiKey());
            body.put("query", query);
            body.put("max_results", config.maxResults());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_API))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("市场行情搜索返回非 200: status={}", response.statusCode());
                return List.of();
            }
            List<CandidateReportResponse.MarketRef> refs = new ArrayList<>();
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    String url = item.path("url").asText("");
                    String resultTitle = item.path("title").asText("");
                    if (StringUtils.hasText(url) && refs.size() < MAX_MARKET_REFS) {
                        refs.add(new CandidateReportResponse.MarketRef(
                                StringUtils.hasText(resultTitle) ? resultTitle : url, url));
                    }
                }
            }
            return refs;
        } catch (Exception e) {
            log.warn("市场行情搜索失败(不阻断报告): {}", e.getMessage());
            return List.of();
        }
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
                                               CandidateResumeEntity resume,
                                               MatchResultEntity match) {
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
                match == null ? null : match.getMatchScore(),
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

    private String normalizeRecommend(String recommend) {
        String r = recommend == null ? "" : recommend.trim().toUpperCase(Locale.ROOT);
        return switch (r) {
            case "STRONG_YES" -> "STRONG_YES";
            case "NO" -> "NO";
            default -> "YES";
        };
    }

    private String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private CandidateResumeEntity requireOwnedResume(Long resumeId) {
        if (resumeId == null) {
            throw new IllegalArgumentException("简历 ID 不能为空");
        }
        CandidateResumeEntity resume = candidateResumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new IllegalArgumentException("简历不存在");
        }
        requireOwnedPosition(resume.getPositionId());
        return resume;
    }

    private JobPositionEntity requireOwnedPosition(Long positionId) {
        JobPositionEntity position = jobPositionMapper.selectById(positionId);
        if (position == null) {
            throw new IllegalArgumentException("岗位不存在");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null && !userId.equals(position.getUserId())) {
            throw new IllegalArgumentException("岗位不存在或无权操作");
        }
        return position;
    }
}
