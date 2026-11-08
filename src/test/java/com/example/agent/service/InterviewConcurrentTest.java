package com.example.agent.service;

import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.service.company.CompanyService;
import com.example.agent.service.company.InterviewAssignmentService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class InterviewConcurrentTest {
    @Autowired CompanyService companies;
    @Autowired RecruitmentService recruitment;
    @Autowired InterviewAssignmentService interviews;
    @Autowired com.example.agent.service.company.RecruitmentOutcomeService outcomes;
    @Autowired JdbcTemplate db;
    @MockBean AiGateway ai;
    long owner,interviewer,company,job,resume;
    void login(long id) {
        var identity=new UsernamePasswordAuthenticationToken("fixture",null,List.of());identity.setDetails(Map.of("userId",id));SecurityContextHolder.getContext().setAuthentication(identity);
    }
    @BeforeEach void setup() {
        String name="ivrace_"+UUID.randomUUID().toString().substring(0,16);
        db.update("INSERT INTO sys_user(username,password,role) VALUES (?,'unused','USER')",name);
        owner=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);login(owner);company=companies.current().id();
        interviewer=companies.addMember(new CompanyService.NewMember("ivc_"+owner,"fixture-password","INTERVIEWER")).userId();
        var input=new PositionRequest();input.setTitle("并发面试测试");job=recruitment.createPosition(input).id();
        db.update("INSERT INTO candidate_resume(position_id,user_id,candidate_name,parse_status) VALUES (?,?,'并发候选人','PARSED')",job,owner);
        resume=db.queryForObject("SELECT id FROM candidate_resume WHERE position_id=?",Long.class,job);
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        outcomes.cleanup(resume);interviews.cleanupForResume(resume);db.update("DELETE FROM recruitment_position_activity WHERE position_id=?",job);
        db.update("DELETE FROM candidate_resume WHERE id=?",resume);db.update("DELETE FROM job_position WHERE id=?",job);
        db.update("DELETE FROM recruitment_company_member WHERE company_id=?",company);db.update("DELETE FROM recruitment_company WHERE id=?",company);
        db.update("DELETE FROM sys_user WHERE id IN (?,?)",owner,interviewer);
    }
    <T> List<T> race(Callable<T> action) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var pending=new ArrayList<Future<T>>();for(int i=0;i<2;i++) pending.add(pool.submit(()->{gate.await();try{return action.call();}finally{SecurityContextHolder.clearContext();}}));
            gate.countDown();var result=new ArrayList<T>();for(var item:pending) result.add(item.get(10,TimeUnit.SECONDS));return result;
        } finally {pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"DECIDE","ARCHIVE","REOPEN"})
    void concurrentOutcomeChangesHaveOneWinnerAndOneHistoryEntry(String action) throws Exception {
        long editor=companies.addMember(new CompanyService.NewMember("outcome_editor_"+owner,"fixture-password","HR")).userId();
        try {
            db.update("INSERT INTO recruitment_position_viewer(position_id,user_id,granted_by,permission,revision,created_at) VALUES (?,?,?,'EDITOR',?,CURRENT_TIMESTAMP)",job,editor,owner,UUID.randomUUID().toString());
            int version=0;
            if(!"DECIDE".equals(action)) {outcomes.change(resume,new com.example.agent.service.company.RecruitmentOutcomeService.Input(0,"DECIDE","HIRED","人工复核"));version++;}
            if("REOPEN".equals(action)) {outcomes.change(resume,new com.example.agent.service.company.RecruitmentOutcomeService.Input(1,"ARCHIVE",null,"流程结束"));version++;}
            int expected=version;
            var counter=new java.util.concurrent.atomic.AtomicInteger();
            var wins=race(()->{
                login(counter.getAndIncrement()==0?owner:editor);
                try {outcomes.change(resume,new com.example.agent.service.company.RecruitmentOutcomeService.Input(expected,action,"HIRED","并发复核"));return true;}
                catch(com.example.agent.service.company.InterviewScheduleConflict conflict) {assertThat(conflict.code()).isEqualTo("OUTCOME_VERSION");return false;}
            });
            assertThat(wins).containsExactlyInAnyOrder(true,false);login(owner);
            assertThat(outcomes.get(resume).version()).isEqualTo(expected+1);
            assertThat(outcomes.history(resume)).hasSize(expected+1);
            assertThat(outcomes.history(resume).get(0).actorId()).isIn(owner,editor);
        } finally {
            login(owner);db.update("DELETE FROM recruitment_position_viewer WHERE position_id=? AND user_id=?",job,editor);
            db.update("DELETE FROM recruitment_company_member WHERE user_id=?",editor);db.update("DELETE FROM sys_user WHERE id=?",editor);
        }
    }
    @Test void deletingResumeRacingOutcomeWriteLeavesNoOrphan() throws Exception {
        var counter=new java.util.concurrent.atomic.AtomicInteger();
        race(()->{
            login(owner);
            if(counter.getAndIncrement()==0) recruitment.deleteResume(resume);
            else try {outcomes.change(resume,new com.example.agent.service.company.RecruitmentOutcomeService.Input(0,"DECIDE","HIRED","并发记录"));}
            catch(ResponseStatusException missing) {assertThat(missing.getStatusCode().value()).isEqualTo(404);}
            return true;
        });
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_candidate_outcome WHERE resume_id=?",Long.class,resume)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_candidate_outcome_event WHERE resume_id=?",Long.class,resume)).isZero();
    }
    @Test void concurrentAssignmentCreatesOneRecordAndOneActivity() throws Exception {
        var ids=race(()->{login(owner);return interviews.assign(resume,interviewer).id();});
        assertThat(ids).hasSize(2);assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_interview_assignment WHERE resume_id=?",Long.class,resume)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_position_activity WHERE position_id=?",Long.class,job)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentSameVersionWritesNeverOverwriteEachOther(boolean submit) throws Exception {
        long id=interviews.assign(resume,interviewer).id();
        var outcomes=race(()->{
            login(interviewer);
            try { interviews.save(id,new InterviewAssignmentService.EvaluationInput(0,4,"PASS",null,null,UUID.randomUUID().toString(),submit));return true; }
            catch(ResponseStatusException conflict) {assertThat(conflict.getStatusCode().value()).isEqualTo(409);return false;}
        });
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);
        assertThat(db.queryForObject("SELECT version FROM recruitment_interview_assignment WHERE id=?",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_interview_evaluation WHERE assignment_id=?",Long.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_position_activity WHERE position_id=? AND action='INTERVIEW_EVALUATED'",Long.class,job)).isEqualTo(submit?1L:0L);
    }
}
