package com.example.agent.service;

import com.example.agent.entity.*;
import com.example.agent.mapper.*;
import com.example.agent.service.materials.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class MaterialTaskTest {
    @Autowired MaterialTaskService tasks;
    @SpyBean MaterialTaskStore store;
    @Autowired JobPositionMapper positions;
    @Autowired CandidateResumeMapper resumes;
    @Autowired MatchResultMapper matches;
    @Autowired InterviewService interviews;
    @Autowired ReportService reports;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean AiGateway gateway;
    JobPositionEntity position;
    CandidateResumeEntity resume;
    MatchResultEntity match;

    static void login(long owner) {
        var auth=new UsernamePasswordAuthenticationToken("test-owner","unused",List.of());
        auth.setDetails(Map.of("userId",owner)); SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @BeforeEach void setup() throws Exception {
        login(987L);
        position=new JobPositionEntity();position.setUserId(987L);position.setTitle("资料生成测试");
        position.setStatus("OPEN");position.setRequiredSkills("[\"Java\"]");positions.insert(position);
        resume=new CandidateResumeEntity();resume.setPositionId(position.getId());resume.setUserId(987L);
        resume.setCandidateName("测试候选人");resume.setSkills("[\"Java\"]");resume.setRawText("Java project");resume.setParseStatus("PARSED");resumes.insert(resume);
        match=new MatchResultEntity();match.setPositionId(position.getId());match.setResumeId(resume.getId());
        match.setMatchScore(new BigDecimal("80"));match.setHrDecision("APPROVED");matches.insert(match);
        when(gateway.structured(anyString(),anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "interview".equals(i.getArgument(0)) ? questionOutput("Original") : reportOutput("Original");
        });
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        db.update("DELETE FROM recruitment_material_request WHERE resume_id=?",resume.getId());
        db.update("DELETE FROM recruitment_material_task WHERE resume_id=?",resume.getId());
        db.update("DELETE FROM interview_question WHERE resume_id=?",resume.getId());
        db.update("DELETE FROM candidate_report WHERE resume_id=?",resume.getId());
        matches.deleteById(match.getId());resumes.deleteById(resume.getId());positions.deleteById(position.getId());
    }
    String questionOutput(String prefix) throws Exception {
        var items=new ArrayList<Object>();
        for (int i=0;i<6;i++) items.add(Map.of("category",i<3 ? "技术" : i<5 ? "项目" : "行为",
                "question",prefix+i,"assessPoint","验证","referenceAnswer","参考","difficulty","MEDIUM"));
        return json.writeValueAsString(items);
    }
    String reportOutput(String prefix) throws Exception {
        return json.writeValueAsString(Map.of("summary",prefix,"strength","Java","risk","待核实",
                "salarySuggest","待确认","finalRecommend","YES","reportMd","# "+prefix));
    }
    String generate(String kind,String key) {
        var task=tasks.submit(resume.getId(),kind,key);tasks.execute(task.id(),true);
        assertThat(tasks.get(task.id()).status()).isEqualTo("SUCCEEDED");return task.id();
    }

    @Test void bothKindsGenerateOutsideTransactionsAndAllAcceptedKeysStayIdempotent() {
        for (String kind:List.of("QUESTIONS","REPORT")) {
            var submitted=tasks.submit(resume.getId(),kind,"stable-request");
            assertThat(tasks.submit(resume.getId(),kind,"alias-request").id()).isEqualTo(submitted.id());
            SecurityContextHolder.clearContext();tasks.execute(submitted.id(),true);tasks.execute(submitted.id(),true);login(987L);
            assertThat(tasks.get(submitted.id()).status()).isEqualTo("SUCCEEDED");
            assertThat(tasks.submit(resume.getId(),kind,"stable-request").id()).isEqualTo(submitted.id());
            assertThat(tasks.submit(resume.getId(),kind,"alias-request").id()).isEqualTo(submitted.id());
        }
        assertThat(interviews.listQuestions(resume.getId())).hasSize(6);
        assertThat(reports.getReport(resume.getId()).summary()).isEqualTo("Original");
        verify(gateway,times(2)).structured(anyString(),anyString());
    }

    @Test void concurrentSubmissionsReuseOneTask() throws Exception {
        var pool=Executors.newFixedThreadPool(3);var gate=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<String>>();
            for (int i=0;i<3;i++) {
                String key="parallel-request-"+i;
                futures.add(pool.submit(() -> { login(987L);try { gate.await();return tasks.submit(resume.getId(),"QUESTIONS",key).id(); }
                    finally { SecurityContextHolder.clearContext(); } }));
            }
            gate.countDown();var ids=new HashSet<String>();
            for (var f:futures) ids.add(f.get(10,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);verifyNoInteractions(gateway);
        } finally { pool.shutdownNow(); }
    }

    @Test void failuresPreserveOldQuestionsAndReportAndHideProviderDetails() {
        generate("QUESTIONS","old-questions");generate("REPORT","old-report");
        var questionIds=interviews.listQuestions(resume.getId()).stream().map(q -> q.id()).toList();
        var reportId=reports.getReport(resume.getId()).id();
        when(gateway.structured(anyString(),anyString())).thenThrow(new IllegalStateException("private-key-provider-body"));
        for (String kind:List.of("QUESTIONS","REPORT")) {
            var task=tasks.submit(resume.getId(),kind,"failed-generation");tasks.execute(task.id(),true);
            assertThat(tasks.get(task.id()).status()).isEqualTo("FAILED");
            assertThat(tasks.get(task.id()).errorMessage()).doesNotContain("private-key").contains("原内容保留");
        }
        assertThat(interviews.listQuestions(resume.getId()).stream().map(q -> q.id()).toList()).isEqualTo(questionIds);
        assertThat(reports.getReport(resume.getId()).id()).isEqualTo(reportId);
    }

    @Test void cancelledLateReportCannotOverwriteNewGeneration() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(gateway.structured(eq("report"),anyString())).thenAnswer(i -> {
            entered.countDown();if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return reportOutput("Late");
        }).thenReturn(reportOutput("Fresh"));
        var old=tasks.submit(resume.getId(),"REPORT","cancel-old-request");
        var pool=Executors.newSingleThreadExecutor();var worker=pool.submit(() -> tasks.execute(old.id(),true));
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.cancel(old.id()).status()).isEqualTo("CANCELLED");
            generate("REPORT","cancel-new-request");
            release.countDown();worker.get(10,TimeUnit.SECONDS);
            assertThat(reports.getReport(resume.getId()).summary()).isEqualTo("Fresh");
            assertThat(tasks.get(old.id()).status()).isEqualTo("CANCELLED");
        } finally { release.countDown();worker.get(10,TimeUnit.SECONDS);pool.shutdownNow(); }
    }

    @Test void timedOutQuestionResponseCannotReplaceExistingQuestions() throws Exception {
        generate("QUESTIONS","before-timeout");
        var oldIds=interviews.listQuestions(resume.getId()).stream().map(q -> q.id()).toList();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(gateway.structured(eq("interview"),anyString())).thenAnswer(i -> {
            entered.countDown();if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");return questionOutput("Late");
        });
        var task=tasks.submit(resume.getId(),"QUESTIONS","timeout-request");
        var pool=Executors.newSingleThreadExecutor();var worker=pool.submit(() -> tasks.execute(task.id(),true));
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE recruitment_material_task SET deadline_at=? WHERE id=?",java.time.LocalDateTime.now().minusSeconds(1),task.id());
            assertThat(tasks.get(task.id()).errorCode()).isEqualTo("TIMEOUT");
            release.countDown();worker.get(10,TimeUnit.SECONDS);
            assertThat(interviews.listQuestions(resume.getId()).stream().map(q -> q.id()).toList()).isEqualTo(oldIds);
        } finally { release.countDown();worker.get(10,TimeUnit.SECONDS);pool.shutdownNow(); }
    }

    @Test void inputChangeDuringModelCallRejectsCommit() {
        generate("QUESTIONS","before-input-change");
        var ids=interviews.listQuestions(resume.getId()).stream().map(q -> q.id()).toList();
        when(gateway.structured(eq("interview"),anyString())).thenAnswer(i -> {
            resume.setRawText("Changed resume");resumes.updateById(resume);return questionOutput("Changed");
        });
        var task=tasks.submit(resume.getId(),"QUESTIONS","input-change-request");tasks.execute(task.id(),true);
        assertThat(tasks.get(task.id()).errorCode()).isEqualTo("INPUT_CHANGED");
        assertThat(interviews.listQuestions(resume.getId()).stream().map(q -> q.id()).toList()).isEqualTo(ids);
    }

    @Test void rejectionDuringGenerationPreventsReportCommit() {
        when(gateway.structured(eq("report"),anyString())).thenAnswer(i -> {
            match.setHrDecision("REJECTED");matches.updateById(match);return reportOutput("Rejected");
        });
        var task=tasks.submit(resume.getId(),"REPORT","rejected-request");tasks.execute(task.id(),true);
        assertThat(tasks.get(task.id()).errorCode()).isEqualTo("REJECTED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_report WHERE resume_id=?",Integer.class,resume.getId())).isZero();
    }

    @Test void invalidModelOutputDoesNotSilentlyBecomeValid() throws Exception {
        when(gateway.structured(eq("interview"),anyString())).thenReturn(questionOutput("Invalid").replace("MEDIUM","IMPOSSIBLE"));
        var questions=tasks.submit(resume.getId(),"QUESTIONS","invalid-questions");tasks.execute(questions.id(),true);
        assertThat(tasks.get(questions.id()).errorCode()).isEqualTo("INVALID_OUTPUT");
        when(gateway.structured(eq("interview"),anyString())).thenReturn(questionOutput("Invalid").replace("\"question\":\"Invalid0\"","\"question\":123"));
        var typed=tasks.submit(resume.getId(),"QUESTIONS","invalid-question-type");tasks.execute(typed.id(),true);
        assertThat(tasks.get(typed.id()).errorCode()).isEqualTo("INVALID_OUTPUT");
        when(gateway.structured(eq("report"),anyString())).thenReturn(reportOutput("Invalid").replace("\"YES\"","\"UNKNOWN\""));
        var report=tasks.submit(resume.getId(),"REPORT","invalid-report");tasks.execute(report.id(),true);
        assertThat(tasks.get(report.id()).errorCode()).isEqualTo("INVALID_OUTPUT");
    }

    @Test void completionFailureRollsBackReplacementAndPreservesOldIds() {
        generate("QUESTIONS","before-rollback");
        var ids=interviews.listQuestions(resume.getId()).stream().map(q -> q.id()).toList();
        var task=tasks.submit(resume.getId(),"QUESTIONS","rollback-request");
        doThrow(new IllegalStateException("database failure")).when(store).complete(task.id(),null);
        tasks.execute(task.id(),true);
        assertThat(tasks.get(task.id()).status()).isEqualTo("FAILED");
        assertThat(interviews.listQuestions(resume.getId()).stream().map(q -> q.id()).toList()).isEqualTo(ids);
    }

    @Test void oldReportKeepsItsSourceScoreAndIsMarkedStaleAfterRematch() {
        String old=generate("REPORT","score-old-request");
        match.setMatchScore(new BigDecimal("20"));matches.updateById(match);
        assertThat(reports.getReport(resume.getId()).matchScore()).isEqualByComparingTo("80");
        assertThat(tasks.get(old).stale()).isTrue();
        String fresh=generate("REPORT","score-new-request");
        assertThat(reports.getReport(resume.getId()).matchScore()).isEqualByComparingTo("20");
        assertThat(tasks.get(fresh).stale()).isFalse();
    }

    @Test void legacyReportWithoutProvenanceDoesNotBorrowCurrentMatchScore() {
        generate("REPORT","before-legacy-report");
        db.update("INSERT INTO candidate_report (resume_id,position_id,summary,report_md,final_recommend) VALUES (?,?,?,?,?)",
                resume.getId(),position.getId(),"Historical","# Historical","YES");
        assertThat(reports.getReport(resume.getId()).matchScore()).isNull();
        var sorted=reports.listReports(position.getId());
        assertThat(sorted).hasSize(2);
        assertThat(sorted.get(0).matchScore()).isEqualByComparingTo("80");
        assertThat(sorted.get(1).matchScore()).isNull();
    }

    @Test void ownersEligibilityAndLegacySyncCannotBypassTaskProtection() {
        var task=tasks.submit(resume.getId(),"QUESTIONS","owner-request");
        assertThatThrownBy(() -> interviews.generate(resume.getId())).isInstanceOf(MaterialFailure.class);
        login(988L);
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable action:List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> tasks.get(task.id()),() -> tasks.cancel(task.id()),() -> tasks.latest(resume.getId(),"QUESTIONS"),
                () -> tasks.submit(resume.getId(),"QUESTIONS","wrong-owner-request")))
            assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class);
        login(987L);tasks.cancel(task.id());match.setHrDecision("REJECTED");matches.updateById(match);
        assertThatThrownBy(() -> tasks.submit(resume.getId(),"REPORT","ineligible-request")).isInstanceOf(MaterialFailure.class);
        verifyNoInteractions(gateway);
    }
}
