package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.domain.recruitment.PositionResponse;
import com.example.agent.domain.recruitment.ResumeResponse;
import com.example.agent.domain.recruitment.ResumeUploadResponse;
import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.DocumentEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.DocumentMapper;
import com.example.agent.mapper.InterviewQuestionMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.mapper.MatchResultMapper;
import com.example.agent.mapper.CandidateReportMapper;
import com.example.agent.rag.PdfTextExtractor;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.AiGateway;
import com.example.agent.service.KnowledgeService;
import com.example.agent.service.RecruitmentService;
import com.example.agent.service.ResumeParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentServiceImpl implements RecruitmentService {

    /** 送入 LLM 解析的最大文本长度，超出截断（防超长简历撑爆上下文） */
    private static final int MAX_LLM_TEXT = 12000;

    private final JobPositionMapper jobPositionMapper;
    private final CandidateResumeMapper candidateResumeMapper;
    private final MatchResultMapper matchResultMapper;
    private final InterviewQuestionMapper interviewQuestionMapper;
    private final CandidateReportMapper candidateReportMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeService knowledgeService;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final ResumeParser resumeParser;
    private final com.example.agent.service.resumes.ResumeProfileReader resumeProfiles;
    private final com.example.agent.service.company.PositionDepartmentService positionDepartments;

    // ==================== 岗位管理 ====================

    @Override
    public PositionResponse createPosition(PositionRequest request) {
        validatePosition(request);
        Long userId = SecurityUtils.getCurrentUserId();
        JobPositionEntity entity = new JobPositionEntity();
        applyRequest(entity, request);
        entity.setUserId(userId);
        entity.setStatus("OPEN");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        positionDepartments.create(entity, request.getDepartmentId());
        return toResponse(entity, 0);
    }

    @Override
    public PositionResponse importPosition(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的 JD 文档");
        }
        String content = extractText(file);
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("JD 文档内容为空，无法解析");
        }

        // LLM 结构化抽取 JD 字段
        PositionRequest request = extractJdByLlm(content);
        if (!StringUtils.hasText(request.getTitle())) {
            // 抽取失败兜底：用文件名当岗位名
            String fileName = file.getOriginalFilename();
            request.setTitle(fileName != null ? fileName.replaceAll("\\.[^.]+$", "") : "未命名岗位");
        }

        // JD 原文存入知识库（供后续 RAG 匹配与出题检索）
        String documentId = null;
        try {
            documentId = knowledgeService.upload(file).documentId();
        } catch (Exception e) {
            log.warn("JD 文档入库失败，岗位仍创建: {}", e.getMessage());
        }

        PositionResponse created = createPosition(request);
        if (documentId != null) {
            JobPositionEntity entity = jobPositionMapper.selectById(created.id());
            entity.setDocId(documentId);
            jobPositionMapper.updateById(entity);
            created = toResponse(entity, 0);
        }
        return created;
    }

    @Override
    public List<PositionResponse> listPositions() {
        Long userId = SecurityUtils.getCurrentUserId();
        LambdaQueryWrapper<JobPositionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(JobPositionEntity::getUserId, userId)
                .orderByDesc(JobPositionEntity::getCreatedAt);
        List<JobPositionEntity> positions = jobPositionMapper.selectList(wrapper);

        List<PositionResponse> result = new ArrayList<>();
        for (JobPositionEntity entity : positions) {
            Long count = candidateResumeMapper.selectCount(
                    new LambdaQueryWrapper<CandidateResumeEntity>()
                            .eq(CandidateResumeEntity::getPositionId, entity.getId()));
            result.add(toResponse(entity, count.intValue()));
        }
        return result;
    }

    @Override
    public PositionResponse getPosition(Long id) {
        JobPositionEntity entity = requireOwnedPosition(id);
        Long count = candidateResumeMapper.selectCount(
                new LambdaQueryWrapper<CandidateResumeEntity>()
                        .eq(CandidateResumeEntity::getPositionId, id));
        return toResponse(entity, count.intValue());
    }

    @Override
    public PositionResponse updatePosition(Long id, PositionRequest request) {
        validatePosition(request);
        JobPositionEntity entity = requireOwnedPosition(id);
        applyRequest(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        positionDepartments.update(entity, request.getDepartmentId());
        Long count = candidateResumeMapper.selectCount(
                new LambdaQueryWrapper<CandidateResumeEntity>()
                        .eq(CandidateResumeEntity::getPositionId, id));
        return toResponse(entity, count.intValue());
    }

    @Override
    public void closePosition(Long id) {
        JobPositionEntity entity = requireOwnedPosition(id);
        entity.setStatus("CLOSED");
        entity.setUpdatedAt(LocalDateTime.now());
        jobPositionMapper.updateById(entity);
    }

    @Override
    @Transactional
    public void deletePosition(Long id) {
        requireOwnedPosition(id);
        // 删除岗位下全部简历（连带向量）
        List<CandidateResumeEntity> resumes = candidateResumeMapper.selectList(
                new LambdaQueryWrapper<CandidateResumeEntity>()
                        .eq(CandidateResumeEntity::getPositionId, id));
        for (CandidateResumeEntity resume : resumes) {
            deleteResumeInternal(resume);
        }
        // 删除关联的匹配结果/面试题/报告（M2/M3 数据）
        matchResultMapper.delete(new LambdaQueryWrapper<com.example.agent.entity.MatchResultEntity>()
                .eq(com.example.agent.entity.MatchResultEntity::getPositionId, id));
        interviewQuestionMapper.delete(new LambdaQueryWrapper<com.example.agent.entity.InterviewQuestionEntity>()
                .eq(com.example.agent.entity.InterviewQuestionEntity::getPositionId, id));
        candidateReportMapper.delete(new LambdaQueryWrapper<com.example.agent.entity.CandidateReportEntity>()
                .eq(com.example.agent.entity.CandidateReportEntity::getPositionId, id));
        jobPositionMapper.deleteById(id);
        log.info("岗位已删除: positionId={}, cascadedResumes={}", id, resumes.size());
    }

    // ==================== 简历管理 ====================

    @Override
    public ResumeUploadResponse uploadResumes(Long positionId, List<MultipartFile> files) {
        requireOwnedPosition(positionId);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的简历文件");
        }

        List<ResumeUploadResponse.ResumeUploadItem> items = new ArrayList<>();
        for (MultipartFile file : files) {
            String fileName = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename() : "unnamed";
            try {
                CandidateResumeEntity resume = uploadSingleResume(positionId, file, fileName);
                items.add(new ResumeUploadResponse.ResumeUploadItem(
                        fileName, "PARSED".equals(resume.getParseStatus()), resume.getId(), resume.getCandidateName(), resume.getParseError()));
            } catch (Exception e) {
                // 单份失败不阻断批量
                log.error("简历上传失败: positionId={} file={}", positionId, fileName, e);
                items.add(new ResumeUploadResponse.ResumeUploadItem(
                        fileName, false, null, null, e.getMessage()));
            }
        }
        int success = (int) items.stream().filter(ResumeUploadResponse.ResumeUploadItem::success).count();
        return new ResumeUploadResponse(files.size(), success, files.size() - success, items);
    }

    /**
     * 单份简历入库：文档管道（解析/分块/向量化）→ LLM 结构化抽取 → 落库。
     */
    private CandidateResumeEntity uploadSingleResume(Long positionId, MultipartFile file, String fileName) {
        // 1. 复用知识库管道：解析 → 分块 → Embedding → 向量库 + chunk 持久化
        String documentId = knowledgeService.upload(file).documentId();

        // 2. 提取全文供 LLM 分析
        String rawText = extractText(file);
        if (!StringUtils.hasText(rawText)) {
            throw new IllegalArgumentException("简历内容为空（可能是扫描版 PDF）");
        }

        // 3. LLM 结构化抽取
        CandidateResumeEntity entity = new CandidateResumeEntity();
        entity.setPositionId(positionId);
        entity.setUserId(SecurityUtils.getCurrentUserId());
        entity.setDocId(documentId);
        entity.setRawText(rawText.length() > 60000 ? rawText.substring(0, 60000) : rawText);
        entity.setCreatedAt(LocalDateTime.now());

        try {
            var profile = resumeProfiles.read(resumeParser.parse(rawText));
            entity.setCandidateName(profile.getCandidateName());
            entity.setEmail(profile.getEmail());
            entity.setEducation(profile.getEducation());
            entity.setLastCompany(profile.getLastCompany());
            entity.setYearsExp(profile.getYearsExp());
            entity.setSkills(profile.getSkills());
            entity.setParseStatus("PARSED");
        } catch (Exception e) {
            // 结构化抽取失败：简历仍保留（可人工补录），标记 FAILED
            log.warn("简历 LLM 解析失败，标记 FAILED: type={}", e.getClass().getSimpleName());
            entity.setParseStatus("FAILED");
            entity.setParseError(e instanceof com.example.agent.service.resumes.ResumeParseFailure ? e.getMessage()
                    : "简历解析失败，请检查 AI 服务后重新解析");
        }
        candidateResumeMapper.insert(entity);
        return entity;
    }

    @Override
    public List<ResumeResponse> listResumes(Long positionId) {
        requireOwnedPosition(positionId);
        List<CandidateResumeEntity> resumes = candidateResumeMapper.selectList(
                new LambdaQueryWrapper<CandidateResumeEntity>()
                        .eq(CandidateResumeEntity::getPositionId, positionId)
                        .orderByDesc(CandidateResumeEntity::getCreatedAt));
        List<ResumeResponse> result = new ArrayList<>();
        for (CandidateResumeEntity entity : resumes) {
            String fileName = findDocumentName(entity.getDocId());
            result.add(ResumeResponse.from(entity, fileName, readSkills(entity.getSkills())));
        }
        return result;
    }

    @Override
    @Transactional
    public void deleteResume(Long resumeId) {
        CandidateResumeEntity resume = candidateResumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new IllegalArgumentException("简历不存在");
        }
        JobPositionEntity position = requireOwnedPosition(resume.getPositionId());
        // 权限：简历必须挂在当前用户自己的岗位下
        if (position == null) {
            throw new IllegalArgumentException("简历不存在或无权删除");
        }
        deleteResumeInternal(resume);
    }

    private void deleteResumeInternal(CandidateResumeEntity resume) {
        // 连带删除向量与文档元数据
        if (StringUtils.hasText(resume.getDocId())) {
            try {
                knowledgeService.deleteDocument(resume.getDocId(), resume.getUserId());
            } catch (Exception e) {
                log.warn("删除简历关联文档失败: docId={} err={}", resume.getDocId(), e.getMessage());
            }
        }
        matchResultMapper.delete(new LambdaQueryWrapper<com.example.agent.entity.MatchResultEntity>()
                .eq(com.example.agent.entity.MatchResultEntity::getResumeId, resume.getId()));
        interviewQuestionMapper.delete(new LambdaQueryWrapper<com.example.agent.entity.InterviewQuestionEntity>()
                .eq(com.example.agent.entity.InterviewQuestionEntity::getResumeId, resume.getId()));
        candidateReportMapper.delete(new LambdaQueryWrapper<com.example.agent.entity.CandidateReportEntity>()
                .eq(com.example.agent.entity.CandidateReportEntity::getResumeId, resume.getId()));
        candidateResumeMapper.deleteById(resume.getId());
    }

    // ==================== LLM 结构化抽取 ====================

    private PositionRequest extractJdByLlm(String jdText) {
        PositionRequest request = new PositionRequest();
        try {
            String prompt = """
                    你是招聘助手。从以下 JD（岗位描述）文本中抽取结构化信息，只返回一个 JSON 对象，\
                    不要任何解释文字、不要 markdown 代码块。字段定义：
                    {"title":"岗位名称","department":"部门","city":"城市","salaryMin":0,"salaryMax":0,\
                    "requiredSkills":["必须技能"],"preferredSkills":["加分技能"],"minYears":0,\
                    "education":"学历要求","description":"JD 核心职责概述(200字内)"}
                    规则：找不到的字段返回空字符串/空数组/0；技能只保留关键词。
                    JD 文本：
                    """;
            prompt += truncate(jdText, MAX_LLM_TEXT);
            JsonNode node = callLlmForJson(prompt);
            request.setTitle(textOrNull(node, "title"));
            request.setDepartment(textOrNull(node, "department"));
            request.setCity(textOrNull(node, "city"));
            request.setSalaryMin(node.hasNonNull("salaryMin") ? node.get("salaryMin").asInt() : null);
            request.setSalaryMax(node.hasNonNull("salaryMax") ? node.get("salaryMax").asInt() : null);
            request.setRequiredSkills(readStringArray(node.get("requiredSkills")));
            request.setPreferredSkills(readStringArray(node.get("preferredSkills")));
            request.setMinYears(node.hasNonNull("minYears") ? node.get("minYears").asInt() : 0);
            request.setEducation(textOrNull(node, "education"));
            request.setDescription(textOrNull(node, "description"));
        } catch (Exception e) {
            log.warn("JD LLM 抽取失败，使用兜底值: {}", e.getMessage());
        }
        return request;
    }

    /**
     * 调用 LLM 并把返回文本规整为 JSON 节点（剥掉 ```json 包裹等干扰）。
     */
    private JsonNode callLlmForJson(String prompt) {
        String raw = aiGateway.structured("position", prompt);
        String json = stripToJson(raw);
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("LLM 返回的 JSON 无法解析: " + truncate(json, 200), e);
        }
    }

    /** 从 LLM 返回文本中剥离 markdown 代码块等噪声，定位到 JSON 主体。 */
    static String stripToJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM 返回为空");
        }
        int fence = raw.indexOf("```");
        if (fence >= 0) {
            int lineEnd = raw.indexOf('\n', fence);
            int fenceClose = raw.indexOf("```", lineEnd);
            if (lineEnd > 0 && fenceClose > lineEnd) {
                return raw.substring(lineEnd + 1, fenceClose).trim();
            }
        }
        int left = raw.indexOf('{');
        int right = raw.lastIndexOf('}');
        if (left >= 0 && right > left) {
            return raw.substring(left, right + 1).trim();
        }
        return raw.trim();
    }

    // ==================== 工具方法 ====================

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

    private void validatePosition(PositionRequest request) {
        if (request == null || !StringUtils.hasText(request.getTitle())) {
            throw new IllegalArgumentException("岗位名称不能为空");
        }
        Integer lower = request.getSalaryMin();
        Integer upper = request.getSalaryMax();
        if (lower == null && upper == null) return; // Existing imports/API callers may omit salary.
        if (lower == null || upper == null || lower < 0 || upper <= lower) {
            throw new IllegalArgumentException("请填写完整薪资范围，薪资下限不能为负，上限至少比下限高 1k");
        }
    }

    private void applyRequest(JobPositionEntity entity, PositionRequest request) {
        entity.setTitle(request.getTitle().trim());
        entity.setDepartment(request.getDepartment());
        entity.setCity(request.getCity());
        entity.setSalaryMin(request.getSalaryMin());
        entity.setSalaryMax(request.getSalaryMax());
        entity.setRequiredSkills(toJsonOrNull(request.getRequiredSkills()));
        entity.setPreferredSkills(toJsonOrNull(request.getPreferredSkills()));
        entity.setMinYears(request.getMinYears() == null ? 0 : request.getMinYears());
        entity.setEducation(request.getEducation());
        entity.setDescription(request.getDescription());
    }

    private PositionResponse toResponse(JobPositionEntity entity, int resumeCount) {
        return PositionResponse.from(entity,
                readSkills(entity.getRequiredSkills()),
                readSkills(entity.getPreferredSkills()),
                resumeCount, positionDepartments.view(entity));
    }

    private List<String> readSkills(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return readStringArray(objectMapper.readTree(json));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                list.add(item.asText().trim());
            }
        }
        return list;
    }

    private String toJsonOrNull(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String findDocumentName(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return null;
        }
        DocumentEntity doc = documentMapper.selectOne(
                new LambdaQueryWrapper<DocumentEntity>()
                        .eq(DocumentEntity::getDocumentId, documentId)
                        .last("LIMIT 1"));
        return doc == null ? null : doc.getFileName();
    }

    /** 根据扩展名提取文本：pdf 走 PDFBox，其余按 UTF-8。 */
    private String extractText(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        String extension = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        try {
            if ("pdf".equals(extension)) {
                String text = PdfTextExtractor.extract(file.getInputStream());
                return StringUtils.hasText(text) ? text : null;
            }
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败: " + e.getMessage(), e);
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
