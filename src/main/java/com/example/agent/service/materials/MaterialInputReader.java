package com.example.agent.service.materials;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.entity.*;
import com.example.agent.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@RequiredArgsConstructor
public class MaterialInputReader {
    private final JobPositionMapper positions;
    private final CandidateResumeMapper resumes;
    private final MatchResultMapper matches;
    private final ObjectMapper json;
    public record Input(JobPositionEntity position, CandidateResumeEntity resume, MatchResultEntity match, String hash) {}
    private record Snapshot(JobPositionEntity position, CandidateResumeEntity resume, MatchResultEntity match) {}

    public Input load(Long resumeId, Long owner, String kind, boolean validate, boolean lock) {
        if (!"QUESTIONS".equals(kind) && !"REPORT".equals(kind)) throw new IllegalArgumentException("不支持的资料类型");
        var initial = resumes.selectById(resumeId);
        if (owner == null || initial == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var position = lock ? positions.lockById(initial.getPositionId()) : positions.selectById(initial.getPositionId());
        if (position == null || !owner.equals(position.getUserId()) || !owner.equals(initial.getUserId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var resume = lock ? resumes.selectOne(new LambdaQueryWrapper<CandidateResumeEntity>()
                .eq(CandidateResumeEntity::getId,resumeId).last("FOR UPDATE")) : initial;
        if (resume == null || !position.getId().equals(resume.getPositionId()) || !owner.equals(resume.getUserId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var match = matches.selectOne(new LambdaQueryWrapper<MatchResultEntity>()
                .eq(MatchResultEntity::getResumeId,resumeId).orderByDesc(MatchResultEntity::getId)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        if (validate) {
            if ("CLOSED".equals(position.getStatus())) throw new MaterialFailure("POSITION_CLOSED","岗位已关闭，不能生成资料");
            if (!"PARSED".equals(resume.getParseStatus())) throw new MaterialFailure("NOT_PARSED","简历尚未解析成功");
            if (match != null && "REJECTED".equals(match.getHrDecision()))
                throw new MaterialFailure("REJECTED","候选人已被淘汰，不再生成资料");
            if ("REPORT".equals(kind) && match == null)
                throw new MaterialFailure("NO_MATCH","请先完成人岗匹配，再生成评估报告");
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(new Snapshot(position,resume,match))));
            return new Input(position,resume,match,hash);
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint material input",e); }
    }
}
