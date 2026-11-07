package com.example.agent.service.matching;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MatchInputReader {
    private final JobPositionMapper positions;
    private final CandidateResumeMapper resumes;
    private final ObjectMapper json;

    public record Input(JobPositionEntity position, List<CandidateResumeEntity> resumes, String hash) {}
    private record Candidate(Long id, String name, String skills, Integer years, String education) {}
    private record Snapshot(Long owner, String title, String required, String preferred, Integer years,
                            String education, String status, List<Candidate> candidates) {}

    public Input load(Long positionId, Long ownerId) {
        var position = positions.selectById(positionId);
        if (ownerId == null || position == null || !ownerId.equals(position.getUserId())) {
            throw new MatchTaskFailure("NOT_FOUND", "岗位不存在或无权操作");
        }
        if ("CLOSED".equals(position.getStatus())) {
            throw new MatchTaskFailure("POSITION_CLOSED", "岗位已关闭，不能执行匹配");
        }
        var candidates = resumes.selectList(new LambdaQueryWrapper<CandidateResumeEntity>()
                .eq(CandidateResumeEntity::getPositionId, positionId)
                .eq(CandidateResumeEntity::getParseStatus, "PARSED"));
        if (candidates.isEmpty()) {
            throw new MatchTaskFailure("NO_RESUMES", "没有已解析的简历，请先上传简历");
        }
        return new Input(position, candidates, fingerprint(position, candidates));
    }

    public String fingerprint(JobPositionEntity position, List<CandidateResumeEntity> candidates) {
        var snapshot = new Snapshot(position.getUserId(), position.getTitle(), position.getRequiredSkills(),
                position.getPreferredSkills(), position.getMinYears(), position.getEducation(), position.getStatus(),
                candidates.stream().sorted(Comparator.comparing(CandidateResumeEntity::getId))
                        .map(r -> new Candidate(r.getId(), r.getCandidateName(), r.getSkills(), r.getYearsExp(), r.getEducation())).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(snapshot)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint matching input", e);
        }
    }
}
