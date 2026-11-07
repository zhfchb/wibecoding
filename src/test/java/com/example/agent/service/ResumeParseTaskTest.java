package com.example.agent.service;

import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.service.resumes.ResumeParseFailure;
import com.example.agent.service.resumes.ResumeParseTaskService;
import com.example.agent.service.resumes.ResumeParseTaskStore;
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

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class ResumeParseTaskTest {
    @Autowired ResumeParseTaskService tasks;
    @SpyBean ResumeParseTaskStore store;
    @Autowired JobPositionMapper positions;
    @Autowired CandidateResumeMapper resumes;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean ResumeParser parser;
    JobPositionEntity position;
    CandidateResumeEntity first;
    CandidateResumeEntity second;

    static void login(long owner) {
        var auth=new UsernamePasswordAuthenticationToken("test-owner","unused",List.of());
        auth.setDetails(Map.of("userId",owner));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @BeforeEach void setup() throws Exception {
        login(987L);
        position=new JobPositionEntity();position.setUserId(987L);position.setTitle("解析恢复测试");position.setStatus("OPEN");
        positions.insert(position);
        first=failed("Java one");second=failed("Java two");
        when(parser.parse(anyString())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return json.readTree("{\"candidate_name\":\"测试人选\",\"skills\":[\"Java\"],\"years_exp\":3}");
        });
    }
    CandidateResumeEntity failed(String text) {
        var r=new CandidateResumeEntity();r.setPositionId(position.getId());r.setUserId(987L);
        r.setParseStatus("FAILED");r.setRawText(text);r.setParseError("原解析失败");r.setDocId("original-document");
        resumes.insert(r);return r;
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        db.update("DELETE FROM recruitment_resume_parse_item WHERE task_id IN (SELECT id FROM recruitment_resume_parse_task WHERE position_id=?)",position.getId());
        db.update("DELETE FROM recruitment_resume_parse_task WHERE position_id=?",position.getId());
        db.update("DELETE FROM candidate_resume WHERE position_id=?",position.getId());
        db.update("DELETE FROM job_position WHERE id=?",position.getId());
    }

    @Test void partialFailurePreservesSuccessAndNeverExposesProviderErrors() throws Exception {
        when(parser.parse("Java two")).thenThrow(new IllegalStateException("private-key-and-provider-response"));
        var task=tasks.submit(position.getId(),"partial-request",List.of(first.getId(),second.getId()));
        SecurityContextHolder.clearContext();tasks.execute(task.id());login(987L);
        var result=tasks.get(task.id());
        assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.succeeded()).isEqualTo(1);assertThat(result.failed()).isEqualTo(1);
        assertThat(result.items().get(1).errorMessage()).doesNotContain("private-key").contains("重新解析");
        var parsed=resumes.selectById(first.getId());
        assertThat(parsed.getParseStatus()).isEqualTo("PARSED");assertThat(parsed.getParseError()).isNull();
        assertThat(parsed.getDocId()).isEqualTo("original-document");assertThat(parsed.getYearsExp()).isEqualTo(3);
        assertThat(resumes.selectById(second.getId()).getParseStatus()).isEqualTo("FAILED");
    }

    @Test void sameRequestIsDurablyIdempotentAndDifferentPayloadConflicts() {
        var task=tasks.submit(position.getId(),"idempotent-request",List.of(first.getId()));
        assertThat(tasks.submit(position.getId(),"idempotent-request",List.of(first.getId())).id()).isEqualTo(task.id());
        assertThatThrownBy(() -> tasks.submit(position.getId(),"idempotent-request",List.of(second.getId())))
                .isInstanceOf(ResumeParseFailure.class).hasMessageContaining("不同简历");
        assertThatThrownBy(() -> tasks.submit(position.getId(),"another-request",List.of(second.getId())))
                .isInstanceOf(ResumeParseFailure.class).hasMessageContaining("已有");
        tasks.execute(task.id());
        assertThat(tasks.submit(position.getId(),"idempotent-request",List.of(first.getId())).id()).isEqualTo(task.id());
        verify(parser,times(1)).parse(anyString());
    }

    @Test void concurrentDuplicateSubmissionsCreateOneTask() throws Exception {
        var pool=Executors.newFixedThreadPool(3);var gate=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<String>>();
            for (int i=0;i<3;i++) futures.add(pool.submit(() -> {
                login(987L);try { gate.await();return tasks.submit(position.getId(),"concurrent-request",List.of(first.getId())).id(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            gate.countDown();var ids=new java.util.HashSet<String>();
            for (var future:futures) ids.add(future.get(10,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);verifyNoInteractions(parser);
        } finally { pool.shutdownNow(); }
    }

    @Test void cancellationRetainsCompletedItemsAndFencesLateModelResponse() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(parser.parse("Java two")).thenAnswer(i -> {
            entered.countDown();if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return json.readTree("{\"skills\":[\"Python\"]}");
        });
        var task=tasks.submit(position.getId(),"cancel-request",List.of(first.getId(),second.getId()));
        var pool=Executors.newSingleThreadExecutor();var worker=pool.submit(() -> tasks.execute(task.id()));
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var cancelled=tasks.cancel(task.id());assertThat(cancelled.succeeded()).isEqualTo(1);assertThat(cancelled.cancelled()).isEqualTo(1);
            release.countDown();worker.get(10,TimeUnit.SECONDS);
            assertThat(tasks.get(task.id()).status()).isEqualTo("CANCELLED");
            assertThat(resumes.selectById(first.getId()).getParseStatus()).isEqualTo("PARSED");
            assertThat(resumes.selectById(second.getId()).getParseStatus()).isEqualTo("FAILED");
        } finally { release.countDown();worker.get(10,TimeUnit.SECONDS);pool.shutdownNow(); }
    }

    @Test void inFlightTimeoutCannotOverwriteRetryResult() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(parser.parse(anyString())).thenAnswer(i -> {
            entered.countDown();if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return json.readTree("{\"skills\":[\"Old\"]}");
        }).thenReturn(json.readTree("{\"skills\":[\"New\"]}"));
        var old=tasks.submit(position.getId(),"old-timeout-request",List.of(first.getId()));
        var pool=Executors.newSingleThreadExecutor();var worker=pool.submit(() -> tasks.execute(old.id()));
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE recruitment_resume_parse_task SET deadline_at=? WHERE id=?",java.time.LocalDateTime.now().minusSeconds(1),old.id());
            assertThat(tasks.get(old.id()).errorCode()).isEqualTo("TIMEOUT");
            var fresh=tasks.submit(position.getId(),"new-timeout-request",List.of(first.getId()));tasks.execute(fresh.id());
            release.countDown();worker.get(10,TimeUnit.SECONDS);
            assertThat(tasks.get(fresh.id()).status()).isEqualTo("SUCCEEDED");
            assertThat(resumes.selectById(first.getId()).getSkills()).isEqualTo("[\"New\"]");
        } finally { release.countDown();worker.get(10,TimeUnit.SECONDS);pool.shutdownNow(); }
    }

    @Test void changedOriginalTextRejectsCommit() {
        when(parser.parse(anyString())).thenAnswer(i -> {
            first.setRawText("Changed input");resumes.updateById(first);
            return json.readTree("{\"skills\":[\"Java\"]}");
        });
        var task=tasks.submit(position.getId(),"changed-input-request",List.of(first.getId()));tasks.execute(task.id());
        assertThat(tasks.get(task.id()).items().get(0).errorCode()).isEqualTo("INPUT_CHANGED");
        assertThat(resumes.selectById(first.getId()).getParseStatus()).isEqualTo("FAILED");
    }

    @Test void successfulCandidateIsNeverReparsedOrOverwritten() {
        var task=tasks.submit(position.getId(),"already-parsed-request",List.of(first.getId()));
        first.setParseStatus("PARSED");resumes.updateById(first);tasks.execute(task.id());
        assertThat(tasks.get(task.id()).items().get(0).errorCode()).isEqualTo("NOT_RETRYABLE");verifyNoInteractions(parser);
    }

    @Test void malformedModelOutputNeverWritesCandidateFields() throws Exception {
        for (String invalid:List.of("{}","{\"skills\":[],\"years_exp\":-1}","{\"skills\":[123]}","{\"skills\":[],\"years_exp\":\"3\"}")) {
            when(parser.parse(anyString())).thenReturn(json.readTree(invalid));
            var task=tasks.submit(position.getId(),"invalid-request-"+java.util.UUID.randomUUID(),List.of(first.getId()));tasks.execute(task.id());
            assertThat(tasks.get(task.id()).items().get(0).errorCode()).isEqualTo("INVALID_PROFILE");
            assertThat(resumes.selectById(first.getId()).getParseStatus()).isEqualTo("FAILED");
        }
    }

    @Test void itemCompletionFailureRollsBackCandidateUpdate() {
        var task=tasks.submit(position.getId(),"rollback-request",List.of(first.getId()));
        doThrow(new IllegalStateException("database-write-failed")).when(store).itemStatus(task.id(),first.getId(),"SUCCEEDED",null,null);
        tasks.execute(task.id());
        assertThat(tasks.get(task.id()).status()).isEqualTo("FAILED");
        assertThat(resumes.selectById(first.getId()).getParseStatus()).isEqualTo("FAILED");
        assertThat(resumes.selectById(first.getId()).getCandidateName()).isNull();
    }

    @Test void ownershipAndEligibilityAreCheckedBeforeTaskCreation() {
        var task=tasks.submit(position.getId(),"owner-request",List.of(first.getId()));
        login(988L);
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable operation:List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> tasks.get(task.id()),() -> tasks.cancel(task.id()),() -> tasks.latest(position.getId()),
                () -> tasks.submit(position.getId(),"wrong-owner-request",List.of(first.getId()))))
            assertThatThrownBy(operation).isInstanceOf(ResponseStatusException.class);
        login(987L);tasks.cancel(task.id());
        first.setRawText("");resumes.updateById(first);
        assertThatThrownBy(() -> tasks.submit(position.getId(),"empty-original-request",List.of(first.getId()))).isInstanceOf(ResumeParseFailure.class);
        assertThatThrownBy(() -> tasks.submit(position.getId(),"duplicated-ids-request",List.of(second.getId(),second.getId()))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(parser);
    }
}
