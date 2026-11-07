package com.example.agent.controller;

import com.example.agent.config.OpenApiConfig;
import com.example.agent.domain.recruitment.CandidateReportResponse;
import com.example.agent.domain.recruitment.InterviewQuestionResponse;
import com.example.agent.domain.recruitment.MatchResultResponse;
import com.example.agent.domain.recruitment.MatchTaskResponse;
import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.domain.recruitment.PositionResponse;
import com.example.agent.domain.recruitment.ResumeResponse;
import com.example.agent.domain.recruitment.ResumeUploadResponse;
import com.example.agent.domain.recruitment.ResumeParseTaskResponse;
import com.example.agent.service.InterviewService;
import com.example.agent.service.MatchService;
import com.example.agent.service.RecruitmentService;
import com.example.agent.service.ReportService;
import com.example.agent.service.matching.MatchTaskService;
import com.example.agent.service.resumes.ResumeParseTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "智能招聘", description = "岗位管理 / 简历批量上传与解析 / 人岗匹配")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
@RestController
@RequestMapping("/api/v1/recruitment")
@RequiredArgsConstructor
public class RecruitmentController {

    private final RecruitmentService recruitmentService;
    private final MatchService matchService;
    private final InterviewService interviewService;
    private final ReportService reportService;
    private final MatchTaskService matchTasks;
    private final ResumeParseTaskService resumeParseTasks;
    private final com.example.agent.service.materials.MaterialTaskService materialTasks;

    // ==================== 岗位管理 ====================

    @Operation(summary = "创建岗位", description = "表单方式创建招聘岗位")
    @PostMapping("/positions")
    public PositionResponse createPosition(@RequestBody PositionRequest request) {
        return recruitmentService.createPosition(request);
    }

    @Operation(summary = "上传JD导入岗位", description = "上传 JD 文档(pdf/txt/md)，LLM 自动抽取岗位信息并创建岗位，JD 原文同步入知识库")
    @PostMapping(value = "/positions/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PositionResponse importPosition(@RequestParam("file") MultipartFile file) {
        return recruitmentService.importPosition(file);
    }

    @Operation(summary = "我的岗位列表", description = "当前登录 HR 的岗位列表，含每个岗位的简历数")
    @GetMapping("/positions")
    public List<PositionResponse> listPositions() {
        return recruitmentService.listPositions();
    }

    @Operation(summary = "岗位详情")
    @GetMapping("/positions/{id}")
    public PositionResponse getPosition(@PathVariable Long id) {
        return recruitmentService.getPosition(id);
    }

    @Operation(summary = "更新岗位")
    @PutMapping("/positions/{id}")
    public PositionResponse updatePosition(@PathVariable Long id, @RequestBody PositionRequest request) {
        return recruitmentService.updatePosition(id, request);
    }

    @Operation(summary = "关闭岗位", description = "软关闭，数据保留，不再参与匹配")
    @PostMapping("/positions/{id}/close")
    public void closePosition(@PathVariable Long id) {
        recruitmentService.closePosition(id);
    }

    @Operation(summary = "删除岗位", description = "连带删除岗位下全部简历、向量数据、匹配结果、面试题与报告")
    @DeleteMapping("/positions/{id}")
    public void deletePosition(@PathVariable Long id) {
        recruitmentService.deletePosition(id);
    }

    // ==================== 简历管理 ====================

    @Operation(summary = "批量上传简历", description = "支持 pdf/txt/md，自动解析入库+向量化+LLM结构化抽取(姓名/技能/年限/学历)，单份失败不影响其他")
    @PostMapping(value = "/positions/{id}/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResumeUploadResponse uploadResumes(
            @PathVariable("id") Long positionId,
            @RequestParam("files") List<MultipartFile> files) {
        return recruitmentService.uploadResumes(positionId, files);
    }

    @Operation(summary = "岗位下简历列表", description = "含解析状态与结构化字段(技能/年限/学历)")
    @GetMapping("/positions/{id}/resumes")
    public List<ResumeResponse> listResumes(@PathVariable("id") Long positionId) {
        return recruitmentService.listResumes(positionId);
    }

    @Operation(summary = "删除简历", description = "删除简历记录并连带删除其向量数据")
    @DeleteMapping("/resumes/{resumeId}")
    public void deleteResume(@PathVariable Long resumeId) {
        recruitmentService.deleteResume(resumeId);
    }

    // ==================== 简历重新解析 ====================

    public record ResumeParseSubmission(String requestKey, List<Long> resumeIds) {}

    @PostMapping("/positions/{id}/resumes/parse-tasks")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @Operation(summary="重新解析失败简历", description="对已保存原文的失败简历创建持久化批量任务，最多20份，不重复向量化")
    public ResumeParseTaskResponse submitResumeParseTask(
            @PathVariable("id") Long positionId, @RequestBody ResumeParseSubmission request) {
        return resumeParseTasks.submit(positionId,request.requestKey(),request.resumeIds());
    }

    @GetMapping("/positions/{id}/resumes/parse-tasks/latest")
    public ResumeParseTaskResponse latestResumeParseTask(@PathVariable("id") Long positionId) {
        return resumeParseTasks.latest(positionId);
    }

    @GetMapping("/resumes/parse-tasks/{taskId}")
    public ResumeParseTaskResponse getResumeParseTask(@PathVariable String taskId) {
        return resumeParseTasks.get(taskId);
    }

    @PostMapping("/resumes/parse-tasks/{taskId}/cancel")
    public ResumeParseTaskResponse cancelResumeParseTask(@PathVariable String taskId) {
        return resumeParseTasks.cancel(taskId);
    }

    // ==================== 人岗匹配 ====================

    @Operation(summary = "触发人岗匹配", description = "对该岗位下全部已解析简历执行匹配：规则评分(70%) + LLM语义评分(30%)，按总分排名落库并返回结果列表")
    @PostMapping("/positions/{id}/match/run")
    public List<MatchResultResponse> runMatch(@PathVariable("id") Long positionId) {
        return matchTasks.runSync(positionId);
    }

    public record MatchSubmission(String requestKey) {}

    @PostMapping("/positions/{id}/match/tasks")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @Operation(summary = "提交持久化匹配任务", description = "同请求标识幂等，同岗位活动任务复用；新一轮匹配使用新标识")
    public MatchTaskResponse submitMatchTask(
            @PathVariable("id") Long positionId, @RequestBody MatchSubmission request) {
        return matchTasks.submit(positionId, request.requestKey());
    }

    @GetMapping("/positions/{id}/match/tasks/latest")
    public MatchTaskResponse latestMatchTask(@PathVariable("id") Long positionId) {
        return matchTasks.latest(positionId);
    }

    @GetMapping("/match/tasks/{taskId}")
    public MatchTaskResponse getMatchTask(@PathVariable String taskId) {
        return matchTasks.get(taskId);
    }

    @PostMapping("/match/tasks/{taskId}/cancel")
    public MatchTaskResponse cancelMatchTask(@PathVariable String taskId) {
        return matchTasks.cancel(taskId);
    }

    @Operation(summary = "查询匹配结果", description = "返回该岗位最近一次匹配的排名结果，含评分明细/技能命中/技能缺失")
    @GetMapping("/positions/{id}/match/results")
    public List<MatchResultResponse> listMatchResults(@PathVariable("id") Long positionId) {
        return matchService.listMatches(positionId);
    }

    @Operation(summary = "HR 决策", description = "对某条匹配结果进行决策：approved=true 进入面试流程，false 淘汰")
    @PostMapping("/match/{matchId}/decision")
    public void decide(@PathVariable Long matchId, @RequestParam boolean approved) {
        matchService.decide(matchId, approved);
    }

    // ==================== 定制面试题 ====================

    public record MaterialSubmission(String requestKey) {}

    @PostMapping("/resumes/{resumeId}/materials/{kind}/tasks")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @Operation(summary="提交题目或报告生成任务", description="kind为QUESTIONS或REPORT，重复请求复用活动任务")
    public com.example.agent.domain.recruitment.MaterialTaskResponse submitMaterial(
            @PathVariable Long resumeId,@PathVariable String kind,@RequestBody MaterialSubmission request) {
        return materialTasks.submit(resumeId,kind,request.requestKey());
    }

    @GetMapping("/resumes/{resumeId}/materials/{kind}/tasks/latest")
    public com.example.agent.domain.recruitment.MaterialTaskResponse latestMaterial(@PathVariable Long resumeId,@PathVariable String kind) {
        return materialTasks.latest(resumeId,kind);
    }

    @GetMapping("/materials/tasks/{taskId}")
    public com.example.agent.domain.recruitment.MaterialTaskResponse getMaterialTask(@PathVariable String taskId) {
        return materialTasks.get(taskId);
    }

    @PostMapping("/materials/tasks/{taskId}/cancel")
    public com.example.agent.domain.recruitment.MaterialTaskResponse cancelMaterialTask(@PathVariable String taskId) {
        return materialTasks.cancel(taskId);
    }

    @Operation(summary = "生成定制面试题", description = "基于岗位要求+候选人技能/缺失项+简历原文，LLM 生成 6 道面试题(技术3/项目2/行为1)，含考察点与参考答案；重新生成覆盖旧题，已被淘汰的候选人不允许生成")
    @PostMapping("/resumes/{resumeId}/questions/generate")
    public List<InterviewQuestionResponse> generateQuestions(@PathVariable Long resumeId) {
        return interviewService.generate(resumeId);
    }

    @Operation(summary = "查询面试题", description = "返回该候选人已生成的全部面试题")
    @GetMapping("/resumes/{resumeId}/questions")
    public List<InterviewQuestionResponse> listQuestions(@PathVariable Long resumeId) {
        return interviewService.listQuestions(resumeId);
    }

    // ==================== 候选人评估报告 ====================

    @Operation(summary = "生成评估报告", description = "匹配评分+技能画像+市场行情(Tavily搜索) 由 LLM 整合成评估报告：总评/优势/风险/建议薪资/最终推荐(STRONG_YES/YES/NO)+完整Markdown；需先完成匹配，已淘汰候选人拒绝生成，重新生成覆盖旧报告")
    @PostMapping("/resumes/{resumeId}/report/generate")
    public CandidateReportResponse generateReport(@PathVariable Long resumeId) {
        return reportService.generate(resumeId);
    }

    @Operation(summary = "查询评估报告", description = "返回该候选人的评估报告（取最新一份）")
    @GetMapping("/resumes/{resumeId}/report")
    public CandidateReportResponse getReport(@PathVariable Long resumeId) {
        return reportService.getReport(resumeId);
    }

    @Operation(summary = "岗位下报告列表", description = "返回该岗位全部候选人的评估报告，按匹配分降序")
    @GetMapping("/positions/{id}/reports")
    public List<CandidateReportResponse> listReports(@PathVariable("id") Long positionId) {
        return reportService.listReports(positionId);
    }
}
