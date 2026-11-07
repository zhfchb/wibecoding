package com.example.agent.service;

import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.mapper.MatchResultMapper;
import com.example.agent.service.impl.MatchResultWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class MatchReliabilityTest {
    @Autowired MatchService service;
    @Autowired MatchResultWriter writer;
    @Autowired JobPositionMapper positions;
    @Autowired CandidateResumeMapper resumes;
    @Autowired MatchResultMapper matches;
    @MockBean AiGateway gateway;
    JobPositionEntity position;
    CandidateResumeEntity resume;

    @BeforeEach
    void prepare() {
        position = new JobPositionEntity();
        position.setUserId(987L);
        position.setTitle("匹配可靠性测试");
        position.setRequiredSkills("[\"Java\"]");
        position.setStatus("OPEN");
        positions.insert(position);
        resume = new CandidateResumeEntity();
        resume.setPositionId(position.getId());
        resume.setUserId(987L);
        resume.setSkills("[\"Java\"]");
        resume.setParseStatus("PARSED");
        resumes.insert(resume);
    }

    @Test
    void modelRunsOutsideTransactionAndRematchPreservesDecisionAndId() {
        when(gateway.structured(eq("match"), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "[{\"resumeId\":" + resume.getId() + ",\"score\":80,\"hits\":[\"Java\"]}]";
        });
        var first = service.runMatch(position.getId()).get(0);
        assertThat(first.matchScore()).isEqualByComparingTo("94");
        assertThat(first.scoreDetail().scoringStatus()).isEqualTo("COMPLETE");
        service.decide(first.id(), true);
        when(gateway.structured(eq("match"), anyString())).thenThrow(new IllegalStateException("offline"));
        var again = service.runMatch(position.getId()).get(0);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.hrDecision()).isEqualTo("APPROVED");
        assertThat(again.matchScore()).isEqualByComparingTo("100");
        assertThat(again.scoreDetail().llmScore()).isNull();
        assertThat(again.scoreDetail().scoringStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void missingOrInvalidModelScoreIsNotReplacedWithFifty() {
        for (String output : List.of("[]", "[{\"resumeId\":" + resume.getId() + "}]",
                "[{\"resumeId\":" + resume.getId() + ",\"score\":101}]",
                "[{\"resumeId\":" + resume.getId() + ",\"score\":\"80\"}]")) {
            when(gateway.structured(eq("match"), anyString())).thenReturn(output);
            var result = service.runMatch(position.getId()).get(0);
            assertThat(result.scoreDetail().llmScore()).isNull();
            assertThat(result.scoreDetail().scoringStatus()).isEqualTo("DEGRADED");
        }
    }

    @Test
    void noRequiredSkillsSkipsModelAndClosedPositionRejectsBeforeModel() {
        position.setRequiredSkills("[]");
        positions.updateById(position);
        assertThat(service.runMatch(position.getId()).get(0).scoreDetail().scoringStatus()).isEqualTo("RULE_ONLY");
        position.setStatus("CLOSED");
        positions.updateById(position);
        assertThatThrownBy(() -> service.runMatch(position.getId())).hasMessageContaining("关闭");
        verifyNoInteractions(gateway);
    }

    @Test
    void rematchClearsOldSkillHitsWhenSemanticEvidenceIsUnavailable() {
        resume.setSkills("[]");
        resumes.updateById(resume);
        when(gateway.structured(eq("match"), anyString())).thenReturn(
                "[{\"resumeId\":" + resume.getId() + ",\"score\":80,\"hits\":[\"Java\"]}]");
        var first = service.runMatch(position.getId()).get(0);
        assertThat(first.skillHits()).containsExactly("Java");
        when(gateway.structured(eq("match"), anyString())).thenReturn("[]");
        var again = service.runMatch(position.getId()).get(0);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.skillHits()).isEmpty();
        assertThat(again.skillGaps()).containsExactly("Java");
    }

    @Test
    void changedCandidateListRejectsStaleResultsWithoutOverwritingOldScores() {
        when(gateway.structured(eq("match"), anyString())).thenReturn("[]");
        var first = service.runMatch(position.getId()).get(0);
        var result = matches.selectById(first.id());
        result.setRankNo(99);
        resume.setParseStatus("FAILED");
        resumes.updateById(resume);
        assertThatThrownBy(() -> writer.save(position.getId(), List.of(result)))
                .hasMessageContaining("候选人列表已变更");
        assertThat(matches.selectById(first.id()).getRankNo()).isEqualTo(1);
    }

    @Test
    void failedWriteRollsBackAllScoresAndLeavesDecisionIntact() {
        when(gateway.structured(eq("match"), anyString())).thenReturn("[]");
        var first = service.runMatch(position.getId()).get(0);
        service.decide(first.id(), false);
        var valid = matches.selectById(first.id());
        valid.setRankNo(99);
        var invalid = matches.selectById(first.id());
        // Force the second write to fail after the first score update.
        var second = new CandidateResumeEntity();
        second.setPositionId(position.getId()); second.setUserId(987L); second.setParseStatus("PARSED");
        resumes.insert(second);
        invalid.setId(null); invalid.setResumeId(second.getId()); invalid.setHrDecision("x".repeat(100));
        assertThatThrownBy(() -> writer.save(position.getId(), List.of(valid, invalid))).isInstanceOf(RuntimeException.class);
        assertThat(matches.selectById(first.id()).getRankNo()).isEqualTo(1);
        assertThat(matches.selectById(first.id()).getHrDecision()).isEqualTo("REJECTED");
    }
}
