package com.example.agent.controller;

import com.example.agent.config.OpenApiConfig;
import com.example.agent.domain.recruitment.MatchResultResponse;
import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.domain.recruitment.PositionResponse;
import com.example.agent.domain.recruitment.ResumeResponse;
import com.example.agent.domain.recruitment.ResumeUploadResponse;
import com.example.agent.service.MatchService;
import com.example.agent.service.RecruitmentService;
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

    // ==================== 人岗匹配 ====================

    @Operation(summary = "触发人岗匹配", description = "对该岗位下全部已解析简历执行匹配：规则评分(70%) + LLM语义评分(30%)，按总分排名落库并返回结果列表")
    @PostMapping("/positions/{id}/match/run")
    public List<MatchResultResponse> runMatch(@PathVariable("id") Long positionId) {
        return matchService.runMatch(positionId);
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
}
