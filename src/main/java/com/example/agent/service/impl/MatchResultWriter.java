package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.agent.entity.MatchResultEntity;
import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.mapper.MatchResultMapper;
import com.example.agent.service.matching.MatchInputReader;
import com.example.agent.service.matching.MatchTaskStore;
import com.example.agent.service.matching.MatchTaskFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Only database work runs in this transaction; model requests finish before entry. */
@Service
@RequiredArgsConstructor
public class MatchResultWriter {
    private final JobPositionMapper positions;
    private final MatchResultMapper matches;
    private final CandidateResumeMapper resumes;
    private final MatchTaskStore tasks;
    private final MatchInputReader inputs;
    private final ObjectMapper json;

    @Transactional
    public void save(Long positionId, List<MatchResultEntity> results) {
        persist(positionId, results, null);
    }

    @Transactional
    public void saveTask(Long positionId, List<MatchResultEntity> results, String taskId) {
        persist(positionId, results, taskId);
    }

    private void persist(Long positionId, List<MatchResultEntity> results, String taskId) {
        // Serialize concurrent runs for this position, including first-time inserts.
        var position = positions.lockById(positionId);
        if (position == null) {
            throw new IllegalArgumentException("岗位已删除，请刷新页面");
        }
        if ("CLOSED".equals(position.getStatus())) {
            throw new IllegalArgumentException("岗位已关闭，不能执行匹配");
        }
        var task = taskId == null ? null : tasks.lock(taskId);
        if (taskId != null && (task == null || !"RUNNING".equals(task.view().status())
                || !task.view().deadlineAt().isAfter(java.time.LocalDateTime.now()))) {
            throw new MatchTaskFailure("TASK_INACTIVE", "任务已终止或超时，未写入匹配结果");
        }
        var resumeIds = results.stream().map(MatchResultEntity::getResumeId).collect(Collectors.toSet());
        var currentResumes = resumes.selectList(new LambdaQueryWrapper<CandidateResumeEntity>()
                .eq(CandidateResumeEntity::getPositionId, positionId)
                .eq(CandidateResumeEntity::getParseStatus, "PARSED").last("FOR UPDATE"));
        var currentIds = currentResumes.stream()
                .map(CandidateResumeEntity::getId).collect(Collectors.toSet());
        if (task != null && (!positionId.equals(task.view().positionId())
                || !task.ownerId().equals(position.getUserId())
                || !task.inputHash().equals(inputs.fingerprint(position, currentResumes)))) {
            throw new MatchTaskFailure("INPUT_CHANGED", "岗位或简历已变化，原结果保留，请重新匹配");
        }
        if (resumeIds.isEmpty() || !resumeIds.equals(currentIds)) {
            throw new IllegalStateException("候选人列表已变更，请重新匹配");
        }
        var existing = matches.selectList(new LambdaQueryWrapper<MatchResultEntity>()
                .eq(MatchResultEntity::getPositionId, positionId)).stream()
                .collect(Collectors.toMap(MatchResultEntity::getResumeId, Function.identity()));
        for (var result : results) {
            var previous = existing.get(result.getResumeId());
            if (previous == null) {
                matches.insert(result);
            } else {
                // Update score fields explicitly, including nulls; never overwrite HR decisions.
                result.setId(previous.getId());
                matches.update(null, new LambdaUpdateWrapper<MatchResultEntity>()
                        .eq(MatchResultEntity::getId, previous.getId())
                        .set(MatchResultEntity::getMatchScore, result.getMatchScore())
                        .set(MatchResultEntity::getSkillHits, result.getSkillHits())
                        .set(MatchResultEntity::getSkillGaps, result.getSkillGaps())
                        .set(MatchResultEntity::getScoreDetail, result.getScoreDetail())
                        .set(MatchResultEntity::getRankNo, result.getRankNo()));
            }
        }
        matches.delete(new LambdaQueryWrapper<MatchResultEntity>()
                .eq(MatchResultEntity::getPositionId, positionId)
                .notIn(MatchResultEntity::getResumeId, resumeIds));
        if (taskId != null) {
            int degraded = 0;
            for (var result : results) {
                try {
                    if ("DEGRADED".equals(json.readTree(result.getScoreDetail()).path("scoringStatus").asText())) degraded++;
                } catch (Exception e) { throw new IllegalStateException("Invalid score detail", e); }
            }
            tasks.complete(taskId, degraded);
        }
    }
}
