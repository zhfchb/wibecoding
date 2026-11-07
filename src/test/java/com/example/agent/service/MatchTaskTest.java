package com.example.agent.service;

import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.service.matching.MatchTaskService;
import com.example.agent.service.matching.MatchTaskStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class MatchTaskTest {
    @Autowired MatchTaskService tasks;
    @Autowired MatchTaskStore store;
    @Autowired JobPositionMapper positions;
    @Autowired CandidateResumeMapper resumes;
    @Autowired JdbcTemplate db;
    @MockBean AiGateway gateway;
    JobPositionEntity position;
    CandidateResumeEntity resume;

    static void login(long id) {
        var auth = new UsernamePasswordAuthenticationToken("test-owner", "unused", List.of());
        auth.setDetails(Map.of("userId", id));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @BeforeEach void prepare() {
        login(987L);
        position = new JobPositionEntity();
        position.setUserId(987L); position.setTitle("持久化匹配测试");
        position.setRequiredSkills("[\"Java\"]"); position.setStatus("OPEN");
        positions.insert(position);
        resume = new CandidateResumeEntity();
        resume.setPositionId(position.getId()); resume.setUserId(987L);
        resume.setSkills("[\"Java\"]"); resume.setParseStatus("PARSED");
        resumes.insert(resume);
        when(gateway.structured(eq("match"), anyString())).thenReturn(output(80));
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        db.update("DELETE FROM recruitment_match_request WHERE position_id=?", position.getId());
        db.update("DELETE FROM recruitment_match_task WHERE position_id=?", position.getId());
        db.update("DELETE FROM match_result WHERE position_id=?", position.getId());
        db.update("DELETE FROM candidate_resume WHERE position_id=?", position.getId());
        db.update("DELETE FROM job_position WHERE id=?", position.getId());
    }

    String output(int score) { return "[{\"resumeId\":" + resume.getId() + ",\"score\":" + score + "}]"; }
    int resultCount() { return db.queryForObject("SELECT COUNT(*) FROM match_result WHERE position_id=?", Integer.class, position.getId()); }

    @Test void concurrentSubmissionsReuseOneDurableTask() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) {
                String key = "parallel-" + i;
                futures.add(pool.submit(() -> {
                    login(987L);
                    try { gate.await(); return tasks.submit(position.getId(), key).id(); }
                    finally { SecurityContextHolder.clearContext(); }
                }));
            }
            gate.countDown();
            var ids = new java.util.HashSet<String>();
            for (var future : futures) ids.add(future.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_match_task WHERE position_id=?", Integer.class, position.getId())).isEqualTo(1);
            verifyNoInteractions(gateway);
        } finally { pool.shutdownNow(); }
    }

    @Test void completionAndIdempotencyWorkWithoutRequestThreadIdentity() {
        var submitted = tasks.submit(position.getId(), "stable-request");
        assertThat(tasks.submit(position.getId(), "alias-request").id()).isEqualTo(submitted.id());
        assertThat(tasks.latest(position.getId()).id()).isEqualTo(submitted.id());
        SecurityContextHolder.clearContext();
        tasks.execute(submitted.id(), true);
        tasks.execute(submitted.id(), true);
        login(987L);
        var completed = tasks.get(submitted.id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.completedBatches()).isEqualTo(completed.totalBatches());
        assertThat(resultCount()).isEqualTo(1);
        assertThat(tasks.submit(position.getId(), "stable-request").id()).isEqualTo(submitted.id());
        assertThat(tasks.submit(position.getId(), "alias-request").id()).isEqualTo(submitted.id());
        verify(gateway, times(1)).structured(eq("match"), anyString());
    }

    @Test void degradedScoringCompletesWithExplicitWarningCount() {
        when(gateway.structured(eq("match"), anyString())).thenThrow(new IllegalStateException("private-provider-error"));
        var task = tasks.submit(position.getId(), "degraded-request");
        tasks.execute(task.id(), true);
        var completed = tasks.get(task.id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.degradedCount()).isEqualTo(1);
        assertThat(completed.errorMessage()).isNull();
    }

    @Test void cancellingRunningTaskFencesLateWritesEvenAfterNewTaskCompletes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.structured(eq("match"), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return output(10);
        }).thenReturn(output(95));
        var old = tasks.submit(position.getId(), "cancel-old-request");
        var pool = Executors.newSingleThreadExecutor();
        Future<?> worker = pool.submit(() -> tasks.execute(old.id(), true));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.cancel(old.id()).status()).isEqualTo("CANCELLED");
            var fresh = tasks.submit(position.getId(), "cancel-new-request");
            tasks.execute(fresh.id(), true);
            assertThat(tasks.get(fresh.id()).status()).isEqualTo("SUCCEEDED");
            release.countDown(); worker.get(10, TimeUnit.SECONDS);
            assertThat(tasks.get(old.id()).status()).isEqualTo("CANCELLED");
            assertThat(db.queryForObject("SELECT match_score FROM match_result WHERE position_id=?", java.math.BigDecimal.class, position.getId())).isEqualByComparingTo("99");
        } finally { release.countDown(); worker.get(10, TimeUnit.SECONDS); pool.shutdownNow(); }
    }

    @Test void expiredRunningTaskCannotPublishAndRequiresExplicitNewRequest() {
        var old = tasks.submit(position.getId(), "timeout-old-request");
        assertThat(store.claim(old.id())).isTrue();
        db.update("UPDATE recruitment_match_task SET deadline_at=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), old.id());
        assertThat(tasks.get(old.id()).errorCode()).isEqualTo("TIMEOUT");
        tasks.execute(old.id(), false);
        assertThat(resultCount()).isZero();
        assertThat(tasks.submit(position.getId(), "timeout-old-request").id()).isEqualTo(old.id());
        var fresh = tasks.submit(position.getId(), "timeout-new-request");
        assertThat(fresh.id()).isNotEqualTo(old.id());
        tasks.execute(fresh.id(), true);
        assertThat(tasks.get(fresh.id()).status()).isEqualTo("SUCCEEDED");
        verify(gateway, times(1)).structured(eq("match"), anyString());
    }

    @Test void competingWorkersClaimOnlyOneModelExecution() throws Exception {
        var task = tasks.submit(position.getId(), "competing-workers-request");
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                gate.await(); tasks.execute(task.id(), true); return null;
            }));
            gate.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertThat(tasks.get(task.id()).status()).isEqualTo("SUCCEEDED");
            verify(gateway, times(1)).structured(eq("match"), anyString());
            assertThat(resultCount()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void timeoutWhileModelIsInFlightPreventsLateResultWrite() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.structured(eq("match"), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return output(80);
        });
        var task = tasks.submit(position.getId(), "late-timeout-request");
        var pool = Executors.newSingleThreadExecutor();
        var worker = pool.submit(() -> tasks.execute(task.id(), true));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE recruitment_match_task SET deadline_at=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), task.id());
            assertThat(tasks.get(task.id()).errorCode()).isEqualTo("TIMEOUT");
            release.countDown(); worker.get(10, TimeUnit.SECONDS);
            assertThat(resultCount()).isZero();
            assertThat(tasks.get(task.id()).errorCode()).isEqualTo("TIMEOUT");
        } finally { release.countDown(); worker.get(10, TimeUnit.SECONDS); pool.shutdownNow(); }
    }

    @Test void changedInputBeforeExecutionRejectsWithoutCallingModel() {
        var task = tasks.submit(position.getId(), "changed-before-request");
        position.setRequiredSkills("[\"Python\"]"); positions.updateById(position);
        tasks.execute(task.id(), true);
        assertThat(tasks.get(task.id()).errorCode()).isEqualTo("INPUT_CHANGED");
        assertThat(resultCount()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test void interruptedWorkerDoesNotDegradeIntoSuccessfulRuleOnlyResult() {
        var task = tasks.submit(position.getId(), "interrupted-worker-request");
        assertThat(store.claim(task.id())).isTrue();
        Thread.currentThread().interrupt();
        try { tasks.execute(task.id(), false); }
        finally { Thread.interrupted(); }
        assertThat(tasks.get(task.id()).status()).isEqualTo("FAILED");
        assertThat(tasks.get(task.id()).errorCode()).isEqualTo("INTERRUPTED");
        assertThat(resultCount()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test void changedResumeDuringModelCallRejectsCommit() {
        when(gateway.structured(eq("match"), anyString())).thenAnswer(invocation -> {
            resume.setSkills("[\"Python\"]"); resumes.updateById(resume);
            return output(80);
        });
        var task = tasks.submit(position.getId(), "changed-during-request");
        tasks.execute(task.id(), true);
        assertThat(tasks.get(task.id()).errorCode()).isEqualTo("INPUT_CHANGED");
        assertThat(resultCount()).isZero();
    }

    @Test void taskEndpointsEnforceOwnershipAndLegacySyncCannotBypassActiveTask() {
        var task = tasks.submit(position.getId(), "isolation-request");
        assertThatThrownBy(() -> tasks.runSync(position.getId())).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
        login(988L);
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable operation : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> tasks.get(task.id()), () -> tasks.cancel(task.id()), () -> tasks.latest(position.getId()),
                () -> tasks.submit(position.getId(), "wrong-owner-request"))) {
            assertThatThrownBy(operation).isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
        }
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> tasks.submit(position.getId(), "no-owner-request")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(gateway);
    }
}
