package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.domain.recruitment.InterviewQuestionResponse;
import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.InterviewQuestionEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.entity.MatchResultEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.InterviewQuestionMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.mapper.MatchResultMapper;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.AiGateway;
import com.example.agent.service.InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 定制面试题生成（M3）。
 * <p>输入：岗位要求 + 候选人结构化信息 + 匹配结果（命中/缺失技能）+ 简历原文摘要。
 * <p>输出：技术 3 题 + 项目 2 题 + 行为 1 题，每题含考察点、参考答案要点、难度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl implements InterviewService {

    /** 送入 LLM 的简历原文最大长度（项目/行为题需要细节，但防上下文过长） */
    private static final int MAX_RESUME_TEXT = 3000;

    private final JobPositionMapper jobPositionMapper;
    private final CandidateResumeMapper candidateResumeMapper;
    private final MatchResultMapper matchResultMapper;
    private final InterviewQuestionMapper interviewQuestionMapper;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public List<InterviewQuestionResponse> generate(Long resumeId) {
        CandidateResumeEntity resume = requireOwnedResume(resumeId);
        if (!"PARSED".equals(resume.getParseStatus())) {
            throw new IllegalArgumentException("简历尚未解析成功，无法生成面试题");
        }

        // 匹配结果存在且被 HR 淘汰的，不再出题（业务闭环：只有进入面试流程的候选人需要题目）
        MatchResultEntity match = matchResultMapper.selectOne(
                new LambdaQueryWrapper<MatchResultEntity>()
                        .eq(MatchResultEntity::getResumeId, resumeId)
                        .orderByDesc(MatchResultEntity::getId)
                        .last("LIMIT 1"));
        if (match != null && "REJECTED".equals(match.getHrDecision())) {
            throw new IllegalArgumentException("该候选人已被 HR 淘汰，不再生成面试题");
        }

        JobPositionEntity position = jobPositionMapper.selectById(resume.getPositionId());
        if (position == null) {
            throw new IllegalArgumentException("岗位不存在");
        }

        List<InterviewQuestionEntity> questions;
        try {
            questions = callLlm(position, resume, match);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LLM 面试题生成失败: resumeId={}, {}", resumeId, e.getMessage());
            throw new IllegalArgumentException("面试题生成失败，请稍后重试");
        }

        // 重新生成覆盖旧题
        interviewQuestionMapper.delete(new LambdaQueryWrapper<InterviewQuestionEntity>()
                .eq(InterviewQuestionEntity::getResumeId, resumeId));
        for (InterviewQuestionEntity q : questions) {
            q.setResumeId(resumeId);
            q.setPositionId(position.getId());
            q.setCreatedAt(LocalDateTime.now());
            interviewQuestionMapper.insert(q);
        }
        log.info("面试题生成完成: resumeId={} positionId={} count={}", resumeId, position.getId(), questions.size());
        return listQuestions(resumeId);
    }

    @Override
    public List<InterviewQuestionResponse> listQuestions(Long resumeId) {
        CandidateResumeEntity resume = requireOwnedResume(resumeId);
        List<InterviewQuestionEntity> questions = interviewQuestionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestionEntity>()
                        .eq(InterviewQuestionEntity::getResumeId, resumeId)
                        .orderByAsc(InterviewQuestionEntity::getId));
        List<InterviewQuestionResponse> result = new ArrayList<>();
        for (InterviewQuestionEntity q : questions) {
            result.add(new InterviewQuestionResponse(
                    q.getId(), q.getResumeId(), resume.getCandidateName(),
                    q.getCategory(), q.getQuestion(), q.getAssessPoint(),
                    q.getReferenceAnswer(), q.getDifficulty(),
                    q.getCreatedAt() == null ? null : q.getCreatedAt().toString()));
        }
        return result;
    }

    // ==================== LLM 出题 ====================

    private List<InterviewQuestionEntity> callLlm(JobPositionEntity position,
                                                  CandidateResumeEntity resume,
                                                  MatchResultEntity match) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深技术面试官。基于岗位要求与候选人简历，生成定制面试题。\n\n");
        sb.append("【岗位】").append(position.getTitle()).append('\n');
        sb.append("【必须技能】").append(position.getRequiredSkills()).append('\n');
        sb.append("【加分技能】").append(StringUtils.hasText(position.getPreferredSkills())
                ? position.getPreferredSkills() : "无").append('\n');
        sb.append("【最低年限】").append(position.getMinYears() == null ? 0 : position.getMinYears()).append(" 年\n");
        sb.append("【学历要求】").append(StringUtils.hasText(position.getEducation())
                ? position.getEducation() : "不限").append("\n\n");
        sb.append("【候选人】").append(StringUtils.hasText(resume.getCandidateName())
                ? resume.getCandidateName() : "未知").append('\n');
        sb.append("【候选人技能】").append(StringUtils.hasText(resume.getSkills())
                ? resume.getSkills() : "未知").append('\n');
        sb.append("【工作年限】").append(resume.getYearsExp() == null ? 0 : resume.getYearsExp()).append(" 年，学历 ")
                .append(StringUtils.hasText(resume.getEducation()) ? resume.getEducation() : "未知").append('\n');
        if (match != null) {
            sb.append("【技能匹配-命中】").append(StringUtils.hasText(match.getSkillHits())
                    ? match.getSkillHits() : "无").append('\n');
            sb.append("【技能匹配-缺失】").append(StringUtils.hasText(match.getSkillGaps())
                    ? match.getSkillGaps() : "无").append('\n');
        }
        String rawText = resume.getRawText();
        if (StringUtils.hasText(rawText)) {
            String brief = rawText.length() > MAX_RESUME_TEXT ? rawText.substring(0, MAX_RESUME_TEXT) : rawText;
            sb.append("\n【简历原文（截断）】\n").append(brief).append('\n');
        }
        sb.append("""

                生成 6 道面试题：技术题 3 道（针对命中技能深挖、针对缺失技能验证真实性）、项目题 2 道（贴合简历中的真实项目经历）、行为题 1 道。
                技术题难度结合候选人年限：3 年以下偏基础(EASY/MEDIUM)，5 年以上必须有 1 道 HARD。

                只返回 JSON 数组，不要任何解释文字、不要 markdown 代码块，格式：
                [{"category":"技术","question":"题目","assessPoint":"考察点(50字内)","referenceAnswer":"参考答案要点(150字内)","difficulty":"EASY"}]
                category 取值只能是：技术/项目/行为；difficulty 取值只能是：EASY/MEDIUM/HARD。
                """);

        String raw = aiGateway.structured("interview", sb.toString());
        String json = raw == null ? "" : raw.trim();
        int left = json.indexOf('[');
        int right = json.lastIndexOf(']');
        if (left < 0 || right <= left) {
            throw new IllegalArgumentException("LLM 未返回面试题 JSON 数组");
        }
        JsonNode array = objectMapper.readTree(json.substring(left, right + 1));

        List<InterviewQuestionEntity> result = new ArrayList<>();
        for (JsonNode node : array) {
            String question = node.path("question").asText("");
            if (!StringUtils.hasText(question)) {
                continue;
            }
            InterviewQuestionEntity q = new InterviewQuestionEntity();
            q.setCategory(normalizeCategory(node.path("category").asText("技术")));
            q.setQuestion(question.trim());
            q.setAssessPoint(textOrNull(node, "assessPoint"));
            q.setReferenceAnswer(textOrNull(node, "referenceAnswer"));
            q.setDifficulty(normalizeDifficulty(node.path("difficulty").asText("MEDIUM")));
            q.setHrDecision("PENDING");
            result.add(q);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("LLM 未生成有效面试题");
        }
        return result;
    }

    private String normalizeCategory(String category) {
        String c = category == null ? "" : category.trim();
        if (c.contains("项目")) {
            return "项目";
        }
        if (c.contains("行为") || c.contains("软素质")) {
            return "行为";
        }
        return "技术";
    }

    private String normalizeDifficulty(String difficulty) {
        String d = difficulty == null ? "" : difficulty.trim().toUpperCase(Locale.ROOT);
        return switch (d) {
            case "EASY" -> "EASY";
            case "HARD" -> "HARD";
            default -> "MEDIUM";
        };
    }

    private CandidateResumeEntity requireOwnedResume(Long resumeId) {
        if (resumeId == null) {
            throw new IllegalArgumentException("简历 ID 不能为空");
        }
        CandidateResumeEntity resume = candidateResumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new IllegalArgumentException("简历不存在");
        }
        JobPositionEntity position = jobPositionMapper.selectById(resume.getPositionId());
        if (position == null) {
            throw new IllegalArgumentException("简历所属岗位不存在");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null && !userId.equals(position.getUserId())) {
            throw new IllegalArgumentException("简历不存在或无权操作");
        }
        return resume;
    }

    private String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
