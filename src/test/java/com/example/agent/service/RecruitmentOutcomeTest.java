package com.example.agent.service;

import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.service.company.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class RecruitmentOutcomeTest {
    @Autowired RecruitmentOutcomeService outcomes;
    @Autowired RecruitmentService recruitment;
    @Autowired CompanyService companies;
    @Autowired PositionCollaborationService grants;
    @Autowired JdbcTemplate db;
    @MockBean AiGateway ai;
    long owner,editor,viewer,interviewer,outsider,job,resume;
    long account() {
        String name="out_"+UUID.randomUUID().toString().substring(0,18);
        db.update("INSERT INTO sys_user(username,password,role) VALUES (?,'unused','USER')",name);
        return db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    void login(long id) {
        var identity=new UsernamePasswordAuthenticationToken("fixture",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        identity.setDetails(Map.of("userId",id));SecurityContextHolder.getContext().setAuthentication(identity);
    }
    @BeforeEach void setup() {
        owner=account();login(owner);long company=companies.current().id();editor=account();viewer=account();interviewer=account();outsider=account();
        for(long id:List.of(editor,viewer,interviewer)) db.update("INSERT INTO recruitment_company_member(user_id,company_id,role) VALUES (?,?,?)",id,company,id==interviewer?"INTERVIEWER":"HR");
        var input=new PositionRequest();input.setTitle("招聘结果验证");job=recruitment.createPosition(input).id();
        db.update("INSERT INTO candidate_resume(position_id,user_id,candidate_name,parse_status) VALUES (?,?,'结果候选人','PARSED')",job,owner);
        resume=db.queryForObject("SELECT id FROM candidate_resume WHERE position_id=?",Long.class,job);
        grants.grant(job,editor,"EDITOR");grants.grant(job,viewer);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    RecruitmentOutcomeService.State change(int version,String action,String outcome,String reason) {return outcomes.change(resume,new RecruitmentOutcomeService.Input(version,action,outcome,reason));}
    @Test void defaultsWithoutBackfillAndKeepsScreeningIndependent() {
        assertThat(outcomes.get(resume).outcome()).isEqualTo("PENDING");assertThat(outcomes.history(resume)).isEmpty();
        var saved=change(0,"DECIDE","HIRED","人工复核通过");assertThat(saved.version()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM match_result WHERE resume_id=?",Long.class,resume)).isZero();
        assertThat(outcomes.history(resume).get(0).actorId()).isEqualTo(owner);
    }
    @Test void archiveReopenAndCorrectRetainAllHistory() {
        change(0,"DECIDE","REJECTED","技能不符合");assertThat(change(1,"ARCHIVE",null,"流程结束").archived()).isTrue();
        assertThatThrownBy(()->change(2,"DECIDE","HIRED","修改")).isInstanceOf(IllegalArgumentException.class);
        assertThat(change(2,"REOPEN",null,"复核结果").outcome()).isEqualTo("REJECTED");
        change(3,"DECIDE","PENDING","等待补充证据");assertThat(outcomes.history(resume)).hasSize(4);
    }
    @Test void staleVersionDoesNotOverwriteOrDuplicateHistory() {
        change(0,"DECIDE","WITHDRAWN","候选人确认退出");
        assertThatThrownBy(()->change(0,"DECIDE","HIRED","旧页面")).isInstanceOf(InterviewScheduleConflict.class);
        assertThat(outcomes.get(resume).outcome()).isEqualTo("WITHDRAWN");assertThat(outcomes.history(resume)).hasSize(1);
    }
    @Test void editorCanDecideAndViewerOnlyReads() {
        login(editor);change(0,"DECIDE","HIRED","协作人复核");assertThat(outcomes.history(resume).get(0).actorId()).isEqualTo(editor);
        login(viewer);assertThat(outcomes.get(resume).outcome()).isEqualTo("HIRED");
        assertThatThrownBy(()->change(1,"ARCHIVE",null,"无写权限")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void interviewerAndUnrelatedUserCannotReadOrWrite() {
        for(long id:List.of(interviewer,outsider)) {
            login(id);assertThatThrownBy(()->outcomes.get(resume)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(()->outcomes.history(resume)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(()->change(0,"DECIDE","HIRED","越权")).isInstanceOf(ResponseStatusException.class);
        }
    }
    @Test void invalidActionsReasonsAndPendingArchiveRejected() {
        for(var input:List.of(new RecruitmentOutcomeService.Input(0,"ARCHIVE",null,"未决定"),new RecruitmentOutcomeService.Input(0,"REOPEN",null,"未归档"),new RecruitmentOutcomeService.Input(0,"DECIDE","FAKE","未知结果"),new RecruitmentOutcomeService.Input(0,"DECIDE","HIRED"," "),new RecruitmentOutcomeService.Input(0,"DECIDE","HIRED","长".repeat(2001))))
            assertThatThrownBy(()->outcomes.change(resume,input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(outcomes.history(resume)).isEmpty();
    }
    @Test void closedJobStillAllowsAdministrativeResults() {
        db.update("UPDATE job_position SET status='CLOSED' WHERE id=?",job);
        assertThat(change(0,"DECIDE","REJECTED","岗位关闭").outcome()).isEqualTo("REJECTED");
    }
    @Test void deletingSourceCleansOutcomeAndHistory() {
        change(0,"DECIDE","HIRED","已确认");recruitment.deleteResume(resume);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_candidate_outcome WHERE resume_id=?",Long.class,resume)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_candidate_outcome_event WHERE resume_id=?",Long.class,resume)).isZero();
    }
}
