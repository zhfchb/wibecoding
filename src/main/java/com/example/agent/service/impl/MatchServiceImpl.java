package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.domain.recruitment.MatchResultResponse;
import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.entity.MatchResultEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.mapper.MatchResultMapper;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.AiGateway;
import com.example.agent.service.MatchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 人岗匹配引擎（M2）。
 * <p>评分模型：总分 = 0.7 × 规则分 + 0.3 × LLM 语义分。
 * <ul>
 *   <li>规则分（可解释、可复现）：技能命中 60 + 加分技能 15 + 年限 15 + 学历 10</li>
 *   <li>LLM 语义分：一次批量调用判断技能语义等价（如 K8s ≈ Kubernetes、Spring Cloud ≈ 微服务）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchServiceImpl implements MatchService {

    /** 规则分权重 */
    static final double RULE_WEIGHT = 0.7;
    /** LLM 语义分权重 */
    static final double LLM_WEIGHT = 0.3;

    /** 一次 LLM 批量评分最多处理的候选人数量（防 prompt 过长） */
    private static final int LLM_BATCH_SIZE = 20;

    private final JobPositionMapper jobPositionMapper;
    private final CandidateResumeMapper candidateResumeMapper;
    private final MatchResultMapper matchResultMapper;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public List<MatchResultResponse> runMatch(Long positionId) {
        JobPositionEntity position = requireOwnedPosition(positionId);

        List<CandidateResumeEntity> resumes = candidateResumeMapper.selectList(
                new LambdaQueryWrapper<CandidateResumeEntity>()
                        .eq(CandidateResumeEntity::getPositionId, positionId)
                        .eq(CandidateResumeEntity::getParseStatus, "PARSED"));
        if (resumes.isEmpty()) {
            throw new IllegalArgumentException("该岗位下没有已解析成功的简历，请先上传简历");
        }

        List<String> required = readSkills(position.getRequiredSkills());
        List<String> preferred = readSkills(position.getPreferredSkills());

        // 1. 规则评分（逐份简历，纯本地计算）
        Map<Long, RuleResult> ruleResults = new HashMap<>();
        for (CandidateResumeEntity resume : resumes) {
            ruleResults.put(resume.getId(),
                    scoreByRule(resume, required, preferred, position));
        }

        // 2. LLM 语义评分（分批，每批最多 20 人一次调用）
        Map<Long, LlmResult> llmResults = scoreByLlm(position, resumes, required);

        // 3. 加权合成 + 排序落库（先清掉该岗位旧结果，匹配以最近一次为准）
        matchResultMapper.delete(new LambdaQueryWrapper<MatchResultEntity>()
                .eq(MatchResultEntity::getPositionId, positionId));

        List<MatchResultEntity> entities = new ArrayList<>();
        for (CandidateResumeEntity resume : resumes) {
            RuleResult rule = ruleResults.get(resume.getId());
            LlmResult llm = llmResults.getOrDefault(resume.getId(), new LlmResult(50, null, List.of(), List.of()));
            int total = (int) Math.round(RULE_WEIGHT * rule.total() + LLM_WEIGHT * llm.score());

            MatchResultEntity entity = new MatchResultEntity();
            entity.setPositionId(positionId);
            entity.setResumeId(resume.getId());
            entity.setMatchScore(java.math.BigDecimal.valueOf(total).setScale(2));
            List<String> hits = mergeDistinct(rule.hits, llm.hits);
            entity.setSkillHits(toJson(hits));
            // LLM 语义命中可能与规则缺失重复（如 Spring Cloud≈Spring Boot），缺失清单剔除已命中项
            entity.setSkillGaps(toJson(excludeHits(mergeDistinct(rule.gaps, llm.gaps), hits)));
            entity.setScoreDetail(buildScoreDetailJson(rule, llm));
            entity.setHrDecision("PENDING");
            entities.add(entity);
        }

        // 按总分降序排名后落库
        entities.sort(Comparator.comparing(
                e -> e.getMatchScore() == null ? 0 : e.getMatchScore().doubleValue(), Comparator.reverseOrder()));
        for (int i = 0; i < entities.size(); i++) {
            entities.get(i).setRankNo(i + 1);
            entities.get(i).setCreatedAt(LocalDateTime.now());
            matchResultMapper.insert(entities.get(i));
        }

        log.info("匹配完成: positionId={} resumes={} top={} score={}",
                positionId, entities.size(),
                entities.isEmpty() ? "-" : entities.get(0).getResumeId(),
                entities.isEmpty() ? "-" : entities.get(0).getMatchScore());
        return listMatches(positionId);
    }

    @Override
    public List<MatchResultResponse> listMatches(Long positionId) {
        requireOwnedPosition(positionId);
        List<MatchResultEntity> results = matchResultMapper.selectList(
                new LambdaQueryWrapper<MatchResultEntity>()
                        .eq(MatchResultEntity::getPositionId, positionId)
                        .orderByAsc(MatchResultEntity::getRankNo));
        if (results.isEmpty()) {
            return List.of();
        }
        // 批量取简历信息
        Map<Long, CandidateResumeEntity> resumeMap = new HashMap<>();
        for (MatchResultEntity result : results) {
            if (!resumeMap.containsKey(result.getResumeId())) {
                CandidateResumeEntity r = candidateResumeMapper.selectById(result.getResumeId());
                if (r != null) {
                    resumeMap.put(r.getId(), r);
                }
            }
        }
        List<MatchResultResponse> response = new ArrayList<>();
        for (MatchResultEntity result : results) {
            CandidateResumeEntity resume = resumeMap.get(result.getResumeId());
            response.add(new MatchResultResponse(
                    result.getId(),
                    result.getResumeId(),
                    resume == null ? null : resume.getCandidateName(),
                    resume == null ? null : resume.getYearsExp(),
                    resume == null ? null : resume.getEducation(),
                    resume == null ? null : resume.getLastCompany(),
                    result.getMatchScore(),
                    result.getRankNo(),
                    readSkills(result.getSkillHits()),
                    readSkills(result.getSkillGaps()),
                    parseScoreDetail(result.getScoreDetail()),
                    result.getHrDecision()));
        }
        return response;
    }

    @Override
    public void decide(Long matchId, boolean approved) {
        MatchResultEntity result = matchResultMapper.selectById(matchId);
        if (result == null) {
            throw new IllegalArgumentException("匹配结果不存在");
        }
        requireOwnedPosition(result.getPositionId());
        result.setHrDecision(approved ? "APPROVED" : "REJECTED");
        matchResultMapper.updateById(result);
    }

    // ==================== 规则评分 ====================

    /** 规则评分结果：分项得分 + 命中/缺失技能 */
    record RuleResult(int skillScore, int preferredScore, int yearsScore, int educationScore,
                      List<String> hits, List<String> gaps) {
        int total() {
            return skillScore + preferredScore + yearsScore + educationScore;
        }
    }

    /**
     * 规则评分（可解释可复现）：
     * 必须技能命中 60 分（按命中率）+ 加分技能 15 分（按命中率）
     * + 年限达标 15 分（不足按比例）+ 学历达标 10 分。
     */
    RuleResult scoreByRule(CandidateResumeEntity resume,
                           List<String> required, List<String> preferred,
                           JobPositionEntity position) {
        Set<String> candidateSkills = new HashSet<>();
        for (String skill : readSkills(resume.getSkills())) {
            candidateSkills.add(normalize(skill));
        }

        List<String> hits = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int skillScore = ratioScore(required, candidateSkills, hits, gaps, 60);
        int preferredScore = ratioScore(preferred, candidateSkills, null, null, 15);

        // 年限：达标满分，不足按比例
        int yearsScore;
        int minYears = position.getMinYears() == null ? 0 : position.getMinYears();
        int actualYears = resume.getYearsExp() == null ? 0 : resume.getYearsExp();
        if (minYears <= 0) {
            yearsScore = 15;
        } else if (actualYears >= minYears) {
            yearsScore = 15;
        } else {
            yearsScore = (int) Math.round(15.0 * actualYears / minYears);
        }

        int educationScore = scoreEducation(position.getEducation(), resume.getEducation());
        return new RuleResult(skillScore, preferredScore, yearsScore, educationScore, hits, gaps);
    }

    /** 按命中率给分，同时收集命中/缺失清单（lists 为 null 时跳过收集）。 */
    private int ratioScore(List<String> required, Set<String> candidateSkills,
                           List<String> hits, List<String> gaps, int fullScore) {
        if (required == null || required.isEmpty()) {
            return fullScore;
        }
        int hit = 0;
        for (String skill : required) {
            if (skillMatch(candidateSkills, skill)) {
                hit++;
                if (hits != null) {
                    hits.add(skill);
                }
            } else if (gaps != null) {
                gaps.add(skill);
            }
        }
        return (int) Math.round(fullScore * hit / (double) required.size());
    }

    /** 技能匹配：精确归一化相等，或双向包含（k8s ⊂ kubernetes）。 */
    static boolean skillMatch(Set<String> candidateSkills, String required) {
        String req = normalize(required);
        if (req.isEmpty()) {
            return true;
        }
        for (String cand : candidateSkills) {
            if (cand.isEmpty()) {
                continue;
            }
            if (cand.equals(req) || cand.contains(req) || req.contains(cand)) {
                return true;
            }
        }
        return false;
    }

    private int scoreEducation(String required, String actual) {
        // 无要求 → 满分
        if (!StringUtils.hasText(required)) {
            return 10;
        }
        String req = normalize(required);
        String act = StringUtils.hasText(actual) ? normalize(actual) : "";
        if (act.equals(req)) {
            return 10;
        }
        // 学历等级比较：大专 < 本科 < 硕士 < 博士
        int reqLevel = eduLevel(req);
        int actLevel = eduLevel(act);
        return actLevel >= reqLevel ? 10 : (actLevel == reqLevel - 1 ? 6 : 2);
    }

    static int eduLevel(String edu) {
        if (edu.contains("博士")) {
            return 4;
        }
        if (edu.contains("硕士") || edu.contains("研究生")) {
            return 3;
        }
        if (edu.contains("本科") || edu.contains("学士") || edu.contains("bachelor")) {
            return 2;
        }
        if (edu.contains("大专") || edu.contains("专科") || edu.contains("college")) {
            return 1;
        }
        return 0;
    }

    // ==================== LLM 语义评分 ====================

    record LlmResult(int score, String notes, List<String> hits, List<String> gaps) {
    }

    /**
     * LLM 批量语义评分：把岗位必须技能 + 全体候选人的技能清单一次性发给 LLM，
     * 让其判断语义等价（K8s≈Kubernetes、Spring Cloud≈微服务开发）并打分。
     * 失败时兜底返回 50 分中性值，不阻断匹配。
     */
    private Map<Long, LlmResult> scoreByLlm(JobPositionEntity position,
                                            List<CandidateResumeEntity> resumes,
                                            List<String> required) {
        Map<Long, LlmResult> results = new HashMap<>();
        if (required.isEmpty()) {
            return results;
        }
        for (int from = 0; from < resumes.size(); from += LLM_BATCH_SIZE) {
            List<CandidateResumeEntity> batch = resumes.subList(from, Math.min(from + LLM_BATCH_SIZE, resumes.size()));
            try {
                results.putAll(callLlmBatch(position, batch, required));
            } catch (Exception e) {
                log.warn("LLM 语义评分失败，本批使用中性分50: {}", e.getMessage());
            }
        }
        return results;
    }

    private Map<Long, LlmResult> callLlmBatch(JobPositionEntity position,
                                              List<CandidateResumeEntity> batch,
                                              List<String> required) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深技术招聘官。根据岗位必须技能，对候选人技能做语义等价判断和评分。")
                .append("语义等价示例：K8s≈Kubernetes、Spring Cloud≈微服务、React≈前端开发、Golang≈Go语言。\n\n");
        sb.append("岗位：").append(position.getTitle()).append('\n');
        sb.append("必须技能：").append(String.join("、", required)).append("\n\n");
        sb.append("候选人列表：\n");
        for (CandidateResumeEntity resume : batch) {
            sb.append("- ID=").append(resume.getId())
                    .append(" 姓名=").append(StringUtils.hasText(resume.getCandidateName()) ? resume.getCandidateName() : "未知")
                    .append(" 技能=").append(String.join("、", readSkills(resume.getSkills())))
                    .append(" 年限=").append(resume.getYearsExp() == null ? 0 : resume.getYearsExp())
                    .append('\n');
        }
        sb.append("""

                只返回 JSON 数组，不要任何解释文字、不要 markdown 代码块，格式：
                [{"resumeId":123,"score":85,"notes":"一句话评价技能匹配情况","hits":["语义等价后算命中的技能"],"gaps":["确实缺失的技能"]}]
                规则：score 为 0-100 整数，表示技能与岗位要求的语义匹配度（结合技能等价与深度经验综合判断）；hits/gaps 只保留必须技能相关项。
                """);

        String raw = aiGateway.structured("match", sb.toString());
        // 批量返回的是 JSON 数组，手动定位 [ ] 主体
        String json = raw == null ? "" : raw.trim();
        int left = json.indexOf('[');
        int right = json.lastIndexOf(']');
        if (left < 0 || right <= left) {
            throw new IllegalArgumentException("LLM 未返回 JSON 数组");
        }
        JsonNode array = objectMapper.readTree(json.substring(left, right + 1));

        Map<Long, LlmResult> results = new HashMap<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                long resumeId = node.path("resumeId").asLong();
                if (resumeId <= 0) {
                    continue;
                }
                results.put(resumeId, new LlmResult(
                        clamp(node.path("score").asInt(50)),
                        textOrNull(node, "notes"),
                        readStringArray(node.get("hits")),
                        readStringArray(node.get("gaps"))));
            }
        }
        return results;
    }

    // ==================== 工具方法 ====================

    private String buildScoreDetailJson(RuleResult rule, LlmResult llm) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("ruleScore", rule.total());
            node.put("llmScore", llm.score());
            ObjectNode breakdown = node.putObject("breakdown");
            breakdown.put("skill", rule.skillScore());
            breakdown.put("preferred", rule.preferredScore());
            breakdown.put("years", rule.yearsScore());
            breakdown.put("education", rule.educationScore());
            node.put("llmNotes", llm.notes());
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    private MatchResultResponse.ScoreDetail parseScoreDetail(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode b = node.path("breakdown");
            return new MatchResultResponse.ScoreDetail(
                    node.path("ruleScore").asInt(),
                    node.path("llmScore").asInt(),
                    b.path("skill").asInt(),
                    b.path("preferred").asInt(),
                    b.path("years").asInt(),
                    b.path("education").asInt(),
                    node.path("llmNotes").asText(null));
        } catch (Exception e) {
            return null;
        }
    }

    private JobPositionEntity requireOwnedPosition(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("岗位 ID 不能为空");
        }
        JobPositionEntity entity = jobPositionMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("岗位不存在");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null && !userId.equals(entity.getUserId())) {
            throw new IllegalArgumentException("岗位不存在或无权操作");
        }
        return entity;
    }

    private List<String> mergeDistinct(List<String> a, List<String> b) {
        Set<String> set = new java.util.LinkedHashSet<>();
        if (a != null) {
            set.addAll(a);
        }
        if (b != null) {
            set.addAll(b);
        }
        return new ArrayList<>(set);
    }

    /** 从缺失清单中剔除已命中的技能（按归一化文本比较，忽略大小写/分隔符差异）。 */
    private List<String> excludeHits(List<String> gaps, List<String> hits) {
        if (gaps == null || gaps.isEmpty() || hits == null || hits.isEmpty()) {
            return gaps;
        }
        Set<String> hitSet = new HashSet<>();
        for (String hit : hits) {
            hitSet.add(normalize(hit));
        }
        List<String> result = new ArrayList<>();
        for (String gap : gaps) {
            if (!hitSet.contains(normalize(gap))) {
                result.add(gap);
            }
        }
        return result;
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readSkills(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return readStringArray(objectMapper.readTree(json));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                list.add(item.asText().trim());
            }
        }
        return list;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        String value = node.path(field).asText(null);
        return StringUtils.hasText(value) ? value : null;
    }

    static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "").replace("·", "").replace("-", "");
    }

    static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
