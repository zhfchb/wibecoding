package com.example.agent.service;

import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.service.company.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class InterviewScheduleTest {
    @Autowired CompanyService companies;
    @Autowired RecruitmentService recruitment;
    @Autowired PositionCollaborationService grants;
    @Autowired InterviewAssignmentService interviews;
    @Autowired InterviewScheduleService schedules;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @MockBean Clock clock;
    @MockBean AiGateway ai;
    static final Instant NOW=Instant.parse("2030-01-01T00:00:00Z");
    long owner,editor,viewer,first,second,company,job1,job2,resume1,resume2,a,b,c;
    final List<Long> users=new ArrayList<>();
    long account() {
        String name="sch_"+UUID.randomUUID().toString().substring(0,18);db.update("INSERT INTO sys_user(username,password,role) VALUES (?,'unused','USER')",name);
        long id=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);users.add(id);return id;
    }
    long member(String role) { long id=account();db.update("INSERT INTO recruitment_company_member(user_id,company_id,role) VALUES (?,?,?)",id,company,role);return id; }
    void login(long id) {
        var identity=new UsernamePasswordAuthenticationToken("fixture",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));identity.setDetails(Map.of("userId",id));SecurityContextHolder.getContext().setAuthentication(identity);
    }
    long job(String title) {var input=new PositionRequest();input.setTitle(title);return recruitment.createPosition(input).id();}
    long resume(long job) {
        db.update("INSERT INTO candidate_resume(position_id,user_id,candidate_name,parse_status) VALUES (?,?,'排期候选人','PARSED')",job,owner);
        return db.queryForObject("SELECT id FROM candidate_resume WHERE position_id=?",Long.class,job);
    }
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);owner=account();login(owner);company=companies.current().id();editor=member("HR");viewer=member("HR");first=member("INTERVIEWER");second=member("INTERVIEWER");
        job1=job("可见岗位");job2=job("其他私有岗位");resume1=resume(job1);resume2=resume(job2);
        grants.grant(job1,editor,"EDITOR");grants.grant(job1,viewer);
        a=interviews.assign(resume1,first).id();b=interviews.assign(resume2,first).id();c=interviews.assign(resume1,second).id();
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();interviews.cleanupForResume(resume1);interviews.cleanupForResume(resume2);
        for(long job:List.of(job1,job2)) {
            db.update("DELETE FROM recruitment_position_activity WHERE position_id=?",job);db.update("DELETE FROM recruitment_position_viewer WHERE position_id=?",job);
            db.update("DELETE FROM candidate_resume WHERE position_id=?",job);db.update("DELETE FROM job_position WHERE id=?",job);
        }
        db.update("DELETE FROM recruitment_company_member WHERE company_id=?",company);db.update("DELETE FROM recruitment_company WHERE id=?",company);
        for(long user:users) db.update("DELETE FROM sys_user WHERE id=?",user);
    }
    InterviewScheduleService.Input input(int version,String start,String end,String zone,String reason) {
        return new InterviewScheduleService.Input(version,LocalDateTime.parse(start),LocalDateTime.parse(end),zone,"VIDEO","会议123","https://meeting.example.test/room",reason);
    }
    InterviewScheduleService.Input standard(int version,String reason) {return input(version,"2030-01-02T10:00","2030-01-02T11:00","Asia/Shanghai",reason);}
    @Test void persistsUtcAndExplicitZoneAndSeparatesScheduleAndEvaluationVersions() {
        var schedule=schedules.put(a,standard(0,null));assertThat(schedule.startAt()).isEqualTo(Instant.parse("2030-01-02T02:00:00Z"));assertThat(schedule.localStart()).isEqualTo(LocalDateTime.parse("2030-01-02T10:00"));
        assertThat(db.queryForObject("SELECT start_utc FROM recruitment_interview_schedule WHERE assignment_id=?",LocalDateTime.class,a)).isEqualTo(LocalDateTime.parse("2030-01-02T02:00"));
        login(first);var detail=interviews.detail(a);assertThat(detail.assignment().flowStatus()).isEqualTo("UPCOMING");assertThat(detail.assignment().version()).isZero();
        interviews.save(a,new InterviewAssignmentService.EvaluationInput(0,null,null,null,null,"准备草稿",false));
        assertThat(interviews.detail(a).assignment().schedule().version()).isEqualTo(1);assertThat(interviews.detail(a).assignment().version()).isEqualTo(1);
        login(owner);schedules.put(a,standard(1,"调整说明"));login(first);assertThat(interviews.detail(a).assignment().evaluation().summary()).isEqualTo("准备草稿");assertThat(interviews.detail(a).assignment().version()).isEqualTo(1);
    }
    @Test void editorCanScheduleOwnScopeButViewersInterviewersAndOtherJobCannot() {
        login(editor);schedules.put(a,standard(0,null));assertThatThrownBy(()->schedules.put(b,standard(0,null))).isInstanceOf(ResponseStatusException.class);
        login(viewer);assertThatThrownBy(()->schedules.put(a,standard(1,"不允许"))).isInstanceOf(ResponseStatusException.class);
        login(first);assertThatThrownBy(()->schedules.history(a)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->schedules.transition(a,new InterviewScheduleService.Transition(1,"CANCEL","无权取消"))).isInstanceOf(ResponseStatusException.class);
        login(owner);assertThat(schedules.history(a).get(0).actorId()).isEqualTo(editor);
    }
    @Test void overlappingInterviewerAcrossJobsAndResumeAcrossInterviewersAreRejected() {
        schedules.put(b,standard(0,null));login(editor);
        assertThatThrownBy(()->schedules.put(a,standard(0,null))).isInstanceOf(InterviewScheduleConflict.class).hasMessageContaining("冲突").hasMessageNotContaining("其他私有岗位");
        login(owner);schedules.transition(b,new InterviewScheduleService.Transition(1,"CANCEL","释放时间"));schedules.put(a,standard(0,null));
        assertThatThrownBy(()->schedules.put(c,standard(0,null))).isInstanceOf(InterviewScheduleConflict.class);
    }
    @Test void adjacencyAndEquivalentUtcAcrossZonesAreHandled() {
        schedules.put(a,standard(0,null));
        schedules.put(b,input(0,"2030-01-02T11:00","2030-01-02T12:00","Asia/Shanghai",null));
        assertThatThrownBy(()->schedules.put(c,input(0,"2030-01-01T21:00","2030-01-01T22:00","America/New_York",null))).isInstanceOf(InterviewScheduleConflict.class);
    }
    @Test void rejectsPastReverseInvalidZoneAndDstGapOrOverlap() {
        for(var invalid:List.of(input(0,"2029-01-02T10:00","2029-01-02T11:00","Asia/Shanghai",null),input(0,"2030-01-02T11:00","2030-01-02T10:00","UTC",null),input(0,"2030-01-02T10:00","2030-01-02T10:03","UTC",null),input(0,"2030-01-02T10:00","2030-01-02T11:00","INVALID",null),input(0,"2030-03-10T02:30","2030-03-10T03:30","America/New_York",null),input(0,"2030-11-03T01:30","2030-11-03T02:30","America/New_York",null)))
            assertThatThrownBy(()->schedules.put(a,invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_interview_schedule WHERE assignment_id=?",Long.class,a)).isZero();
    }
    @Test void invalidMeetingUrlsAndMissingReasonsNeverChangeSchedule() {
        var initial=standard(0,null);
        assertThatThrownBy(()->schedules.put(a,new InterviewScheduleService.Input(0,initial.localStart(),initial.localEnd(),"Asia/Shanghai","VIDEO","会议","javascript:alert(1)",null))).isInstanceOf(IllegalArgumentException.class);
        schedules.put(a,initial);assertThatThrownBy(()->schedules.put(a,standard(1,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->schedules.transition(a,new InterviewScheduleService.Transition(1,"CANCEL",null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(schedules.history(a)).hasSize(1);
    }
    @Test void staleVersionCannotOverwriteOrDuplicateEvents() {
        schedules.put(a,standard(0,null));assertThatThrownBy(()->schedules.put(a,standard(0,"旧版本"))).isInstanceOf(InterviewScheduleConflict.class);
        schedules.put(a,standard(1,"重新核对"));assertThat(schedules.history(a)).extracting("version").containsExactly(2,1);
        assertThatThrownBy(()->schedules.transition(a,new InterviewScheduleService.Transition(1,"CANCEL","旧请求"))).isInstanceOf(InterviewScheduleConflict.class);
    }
    @Test void cancellationFreesTimeKeepsDraftAndRebookingRestoresEvaluationAccess() {
        schedules.put(a,standard(0,null));login(first);interviews.save(a,new InterviewAssignmentService.EvaluationInput(0,null,null,null,null,"草稿保留",false));login(owner);
        schedules.transition(a,new InterviewScheduleService.Transition(1,"CANCEL","候选人改期"));schedules.put(b,standard(0,null));
        login(first);assertThat(interviews.detail(a).assignment().flowStatus()).isEqualTo("CANCELLED");assertThat(interviews.detail(a).assignment().evaluation().summary()).isEqualTo("草稿保留");
        assertThatThrownBy(()->interviews.save(a,new InterviewAssignmentService.EvaluationInput(1,4,"PASS",null,null,"不应保存",true))).isInstanceOf(InterviewScheduleConflict.class);
        login(owner);schedules.put(a,input(2,"2030-01-03T10:00","2030-01-03T11:00","Asia/Shanghai","重新排期"));login(first);
        assertThat(interviews.detail(a).assignment().evaluation().summary()).isEqualTo("草稿保留");interviews.save(a,new InterviewAssignmentService.EvaluationInput(1,null,null,null,null,"仍可准备",false));
    }
    @Test void earlySubmissionAndEarlyCompletionAreBlockedThenEvaluationCompletesSchedule() {
        var schedule=schedules.put(a,standard(0,null));
        assertThatThrownBy(()->schedules.transition(a,new InterviewScheduleService.Transition(1,"COMPLETE",null))).isInstanceOf(InterviewScheduleConflict.class);
        login(first);assertThatThrownBy(()->interviews.save(a,new InterviewAssignmentService.EvaluationInput(0,4,"PASS",null,null,"太早",true))).isInstanceOf(InterviewScheduleConflict.class);
        when(clock.instant()).thenReturn(schedule.endAt().plusSeconds(1));
        var evaluated=interviews.save(a,new InterviewAssignmentService.EvaluationInput(0,4,"PASS",null,null,"面试完成",true));
        assertThat(evaluated.assignment().flowStatus()).isEqualTo("EVALUATED");assertThat(evaluated.assignment().schedule().status()).isEqualTo("COMPLETED");
        login(owner);assertThatThrownBy(()->schedules.put(a,standard(2,"不能改"))).isInstanceOf(InterviewScheduleConflict.class);assertThat(schedules.history(a)).hasSize(2);
    }
    @Test void completedAndNoShowHaveExplicitEvaluationPolicies() {
        var schedule=schedules.put(a,standard(0,null));when(clock.instant()).thenReturn(schedule.endAt().plusSeconds(1));
        schedules.transition(a,new InterviewScheduleService.Transition(1,"COMPLETE",null));login(first);assertThat(interviews.detail(a).assignment().flowStatus()).isEqualTo("AWAITING_EVALUATION");
        when(clock.instant()).thenReturn(NOW);login(owner);var next=schedules.put(b,standard(0,null));when(clock.instant()).thenReturn(next.endAt().plusSeconds(1));
        schedules.transition(b,new InterviewScheduleService.Transition(1,"NO_SHOW","未接通"));login(first);
        assertThat(interviews.detail(b).assignment().flowStatus()).isEqualTo("NO_SHOW");assertThatThrownBy(()->interviews.save(b,new InterviewAssignmentService.EvaluationInput(0,1,"NO",null,null,"不得代评",true))).isInstanceOf(InterviewScheduleConflict.class);
    }
    @Test void revokeCancelsSlotAndDeletionCleansHistory() {
        schedules.put(a,standard(0,null));interviews.revoke(a);assertThat(schedules.history(a).get(0).status()).isEqualTo("CANCELLED");schedules.put(b,standard(0,null));
        recruitment.deleteResume(resume1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_interview_schedule_event WHERE assignment_id=?",Long.class,a)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_interview_schedule WHERE assignment_id=?",Long.class,a)).isZero();
    }
    @Test void closedJobsAllowCancellingExistingSlotsButNotPlanning() {
        schedules.put(a,standard(0,null));recruitment.closePosition(job1);
        assertThatThrownBy(()->schedules.put(a,standard(1,"岗位关闭"))).isInstanceOf(InterviewScheduleConflict.class);
        assertThat(schedules.transition(a,new InterviewScheduleService.Transition(1,"CANCEL","岗位关闭" )).status()).isEqualTo("CANCELLED");
    }
    @Test void httpReturnsConflictCodeAndRejectsInterviewerWrite() throws Exception {
        login(owner);var identity=SecurityContextHolder.getContext().getAuthentication();
        String body="{\"version\":0,\"localStart\":\"2030-01-02T10:00\",\"localEnd\":\"2030-01-02T11:00\",\"timeZone\":\"Asia/Shanghai\",\"mode\":\"VIDEO\",\"location\":\"会议123\"}";
        mvc.perform(put("/api/v1/recruitment/interviews/"+a+"/schedule").with(authentication(identity)).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.startAt").value("2030-01-02T02:00:00Z"));
        mvc.perform(put("/api/v1/recruitment/interviews/"+b+"/schedule").with(authentication(identity)).contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("SCHEDULE_OVERLAP"));
        login(first);var reviewer=SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(put("/api/v1/recruitment/interviews/"+a+"/schedule").with(authentication(reviewer)).contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recruitment/interviews/"+a+"/schedule/history").with(anonymous())).andExpect(status().isUnauthorized());
        verifyNoInteractions(ai);
    }
    void race(long firstId,long secondId) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var attempts=new ArrayList<Future<Boolean>>();for(long id:List.of(firstId,secondId)) attempts.add(pool.submit(()->{
                login(owner);try {gate.await();schedules.put(id,standard(0,null));return true;}
                catch(InterviewScheduleConflict conflict) {assertThat(conflict.code()).isEqualTo("SCHEDULE_OVERLAP");return false;}finally {SecurityContextHolder.clearContext();}
            }));
            gate.countDown();var results=new ArrayList<Boolean>();for(var attempt:attempts) results.add(attempt.get(15,TimeUnit.SECONDS));assertThat(results).containsExactlyInAnyOrder(true,false);
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentCrossJobBookingForSameInterviewerHasOneWinner() throws Exception {race(a,b);}
    @Test void concurrentBookingSameCandidateWithDifferentInterviewersHasOneWinner() throws Exception {race(a,c);}
}
