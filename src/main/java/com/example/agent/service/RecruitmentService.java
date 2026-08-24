package com.example.agent.service;

import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.domain.recruitment.PositionResponse;
import com.example.agent.domain.recruitment.ResumeResponse;
import com.example.agent.domain.recruitment.ResumeUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 智能招聘：岗位与简历管理（M1）
 */
public interface RecruitmentService {

    /** 表单创建岗位 */
    PositionResponse createPosition(PositionRequest request);

    /** 上传 JD 文档，LLM 抽取结构化字段后自动创建岗位 */
    PositionResponse importPosition(MultipartFile file);

    /** 当前用户岗位列表 */
    List<PositionResponse> listPositions();

    /** 岗位详情 */
    PositionResponse getPosition(Long id);

    /** 更新岗位 */
    PositionResponse updatePosition(Long id, PositionRequest request);

    /** 关闭岗位（软关闭，数据保留） */
    void closePosition(Long id);

    /** 删除岗位（连带删除岗位下全部简历与向量） */
    void deletePosition(Long id);

    /** 批量上传简历：解析入库 + 向量化 + LLM 结构化抽取 */
    ResumeUploadResponse uploadResumes(Long positionId, List<MultipartFile> files);

    /** 岗位下简历列表 */
    List<ResumeResponse> listResumes(Long positionId);

    /** 删除单份简历（连带删除向量数据） */
    void deleteResume(Long resumeId);
}
