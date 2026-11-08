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
    void validatesSalaryRangeForCreateAndUpdate() {
        var positions = mock(JobPositionMapper.class);
        var json = new ObjectMapper();
        var departments = mock(com.example.agent.service.company.PositionDepartmentService.class);
        doAnswer(call -> { positions.insert(call.getArgument(0, JobPositionEntity.class)); return null; }).when(departments).create(any(), any());
        when(departments.view(any())).thenAnswer(call -> new com.example.agent.service.company.PositionDepartmentService.View(
                call.getArgument(0, JobPositionEntity.class).getDepartment(),null));
        var service = new RecruitmentServiceImpl(positions, mock(CandidateResumeMapper.class), mock(MatchResultMapper.class),
                mock(InterviewQuestionMapper.class), mock(CandidateReportMapper.class), mock(DocumentMapper.class),
                mock(KnowledgeService.class), mock(AiGateway.class), json, mock(ResumeParser.class),
                new com.example.agent.service.resumes.ResumeProfileReader(json), departments,
                mock(com.example.agent.service.company.PositionAccess.class), mock(com.example.agent.service.company.InterviewAssignmentService.class), mock(com.example.agent.service.company.RecruitmentOutcomeService.class));
        var request = new com.example.agent.domain.recruitment.PositionRequest();
        request.setTitle("薪资校验");
        for (Integer[] range : new Integer[][]{{11,11},{11,10},{-1,12},{null,12},{11,null}}) {
            request.setSalaryMin(range[0]); request.setSalaryMax(range[1]);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createPosition(request))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("高 1k");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updatePosition(1L,request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(positions);
        request.setSalaryMin(11); request.setSalaryMax(12);
        assertThat(service.createPosition(request).salaryMax()).isEqualTo(12);
        request.setSalaryMin(null); request.setSalaryMax(null);
        service.createPosition(request);
        verify(positions,times(2)).insert(any(com.example.agent.entity.JobPositionEntity.class));
    }
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
                knowledge, mock(AiGateway.class), json, parser, new com.example.agent.service.resumes.ResumeProfileReader(json),
                mock(com.example.agent.service.company.PositionDepartmentService.class),
                mock(com.example.agent.service.company.PositionAccess.class), mock(com.example.agent.service.company.InterviewAssignmentService.class), mock(com.example.agent.service.company.RecruitmentOutcomeService.class));
        var file = new MockMultipartFile("file", "resume.md", "text/plain", "Java".getBytes());
        var response = service.uploadResumes(1L, List.of(file, file));
        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.items().get(1).success()).isFalse();
        var captured = ArgumentCaptor.forClass(CandidateResumeEntity.class);
        verify(resumes, times(2)).insert(captured.capture());
        assertThat(captured.getAllValues().get(0).getParseStatus()).isEqualTo("PARSED");
        assertThat(captured.getAllValues().get(0).getYearsExp()).isEqualTo(3);
        assertThat(captured.getAllValues().get(1).getParseStatus()).isEqualTo("FAILED");
        assertThat(captured.getAllValues().get(1).getParseError()).contains("重新解析").doesNotContain("unavailable");
    }
}
