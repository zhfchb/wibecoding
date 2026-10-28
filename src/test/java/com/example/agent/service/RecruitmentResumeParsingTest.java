package com.example.agent.service;

import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.mapper.*;
import com.example.agent.service.impl.RecruitmentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecruitmentResumeParsingTest {
    @Test
    void persistsParsedAndFailedResumesWithoutAbortingBatch() throws Exception {
        var positions = mock(JobPositionMapper.class);
        var resumes = mock(CandidateResumeMapper.class);
        var knowledge = mock(KnowledgeService.class);
        var parser = mock(ResumeParser.class);
        var json = new ObjectMapper();
        when(positions.selectById(1L)).thenReturn(new JobPositionEntity());
        when(knowledge.upload(any())).thenReturn(new KnowledgeUploadResponse("doc", "resume.md", 1));
        when(parser.parse(anyString()))
                .thenReturn(json.readTree("{\"candidate_name\":\"测试\",\"skills\":[\"Java\"],\"years_exp\":3}"))
                .thenThrow(new IllegalStateException("AI service unavailable"));
        var service = new RecruitmentServiceImpl(positions, resumes, mock(MatchResultMapper.class),
                mock(InterviewQuestionMapper.class), mock(CandidateReportMapper.class), mock(DocumentMapper.class),
                knowledge, mock(AiGateway.class), json, parser);
        var file = new MockMultipartFile("file", "resume.md", "text/plain", "Java".getBytes());
        service.uploadResumes(1L, List.of(file, file));
        var captured = ArgumentCaptor.forClass(CandidateResumeEntity.class);
        verify(resumes, times(2)).insert(captured.capture());
        assertThat(captured.getAllValues().get(0).getParseStatus()).isEqualTo("PARSED");
        assertThat(captured.getAllValues().get(0).getYearsExp()).isEqualTo(3);
        assertThat(captured.getAllValues().get(1).getParseStatus()).isEqualTo("FAILED");
        assertThat(captured.getAllValues().get(1).getParseError()).contains("unavailable");
    }
}
