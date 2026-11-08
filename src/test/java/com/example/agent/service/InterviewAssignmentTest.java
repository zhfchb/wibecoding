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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class InterviewAssignmentTest {
    @Autowired CompanyService companies;
    @Autowired RecruitmentService recruitment;
    @Autowired InterviewAssignmentService interviews;
    @Autowired PositionCollaborationService grants;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired com.example.agent.service.matching.MatchTaskService tasks;
    @MockBean AiGateway ai;
    long owner,company,editor,viewer,interviewer,second,foreign,job,resume,otherResume,actor;
    long account() {
        String name="int_"+UUID.randomUUID().toString().substring(0,18);
        db.update("INSERT INTO sys_user(username,password,role) VALUES (?,'unused','USER')",name);
        return db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    void login(long id) {
        actor=id;var identity=new UsernamePasswordAuthenticationToken("fixture",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        identity.setDetails(Map.of("userId",id));SecurityContextHolder.getContext().setAuthentication(identity);
    }
    ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        login(actor);var identity=SecurityContextHolder.getContext().getAuthentication();
        try { return mvc.perform(request.with(authentication(identity))); } finally { login(actor); }
    }
    long member(String prefix,String role) { return companies.addMember(new CompanyService.NewMember(prefix+owner,"fixture-password",role)).userId(); }
    @BeforeEach void setup() {
        owner=account();login(owner);company=companies.current().id();
        editor=member("ed_","HR");viewer=member("view_","HR");interviewer=member("iv_","INTERVIEWER");second=member("iv2_","INTERVIEWER");
        long outsider=account();login(outsider);companies.current();foreign=member("foreign_","INTERVIEWER");login(owner);
        var input=new PositionRequest();input.setTitle("Java面试岗位");input.setRequiredSkills(List.of("Java"));job=recruitment.createPosition(input).id();
        grants.grant(job,editor,"EDITOR");grants.grant(job,viewer);
        for(String name:List.of("候选人甲","未分配候选人乙")) db.update("INSERT INTO candidate_resume(position_id,user_id,candidate_name,email,phone,raw_text,skills,parse_status) VALUES (?,?,?,'private@example.test','13800000000','private-original','[\"Java\"]','PARSED')",job,owner,name);
        var ids=db.queryForList("SELECT id FROM candidate_resume WHERE position_id=? ORDER BY id",Long.class,job);resume=ids.get(0);otherResume=ids.get(1);
        db.update("INSERT INTO interview_question(position_id,resume_id,question,assess_point) VALUES (?,?,'已有Java问题','Java基础')",job,resume);
        db.update("INSERT INTO match_result(position_id,resume_id,hr_decision,match_score) VALUES (?,?,'PENDING',88)",job,resume);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    long assign() { login(owner);return interviews.assign(resume,interviewer).id(); }
    InterviewAssignmentService.EvaluationInput input(int version,boolean submit,String summary) {
        return new InterviewAssignmentService.EvaluationInput(version,4,"PASS","有具体证据","待核实细节",summary,submit);
    }
    @Test void createsDedicatedRoleAndLoginReturnsCompanyRoleWithoutGlobalAdmin() throws Exception {
        assertThat(companies.members()).filteredOn(m->m.userId().equals(interviewer)).extracting("role").containsExactly("INTERVIEWER");
        String username=db.queryForObject("SELECT username FROM sys_user WHERE id=?",String.class,interviewer);
        call(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\""+username+"\",\"password\":\"fixture-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.companyRole").value("INTERVIEWER")).andExpect(jsonPath("$.role").value("USER"));
        assertThatThrownBy(()->companies.addMember(new CompanyService.NewMember("bad_role","fixture-password","ADMIN"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void assignmentsAreIdempotentAndOnlyRecruitmentOperatorsCanAssign() {
        long id=assign();assertThat(interviews.assign(resume,interviewer).id()).isEqualTo(id);
        login(editor);assertThat(interviews.assign(otherResume,second).id()).isPositive();
        login(viewer);assertThatThrownBy(()->interviews.assign(resume,second)).isInstanceOf(ResponseStatusException.class);
        login(interviewer);assertThatThrownBy(()->interviews.assign(resume,second)).isInstanceOf(ResponseStatusException.class);
        login(owner);assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_position_activity WHERE position_id=? AND action='INTERVIEW_ASSIGNED'",Long.class,job)).isEqualTo(2);
    }
    @Test void foreignAndRecruiterTargetsAreRejectedAndInterviewersCannotGetJobGrants() {
        assertThatThrownBy(()->interviews.assign(resume,foreign)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interviews.assign(resume,editor)).isInstanceOf(IllegalArgumentException.class);
        for(String permission:List.of("VIEWER","EDITOR")) assertThatThrownBy(()->grants.grant(job,interviewer,permission)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void interviewerSeesOnlyAssignedCandidateAndMinimumFields() throws Exception {
        long id=assign();interviews.assign(otherResume,second);login(interviewer);
        assertThat(interviews.mine()).extracting("resumeId").containsExactly(resume);
        var detail=interviews.detail(id);assertThat(detail.questions()).extracting("question").containsExactly("已有Java问题");
        String serialized=json.writeValueAsString(detail);
        assertThat(serialized).doesNotContain("private@example.test","13800000000","private-original","未分配候选人乙","matchScore","hrDecision","rawText","docId");
        assertThat(recruitment.listPositions()).isEmpty();assertThatThrownBy(()->recruitment.getPosition(job)).isInstanceOf(ResponseStatusException.class);
        login(second);assertThatThrownBy(()->interviews.detail(id)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void draftsArePrivateEvenFromJobCreatorAndOtherInterviewers() {
        long id=assign();login(interviewer);var detail=interviews.save(id,input(0,false,"私有草稿"));
        assertThat(detail.assignment().status()).isEqualTo("DRAFT");assertThat(detail.assignment().version()).isEqualTo(1);
        assertThat(detail.assignment().evaluation().summary()).isEqualTo("私有草稿");
        login(owner);assertThat(interviews.managed(job)).allSatisfy(a->assertThat(a.evaluation()).isNull());
        assertThatThrownBy(()->interviews.detail(id)).isInstanceOf(ResponseStatusException.class);
        login(editor);assertThat(interviews.managed(job).get(0).evaluation()).isNull();
        login(second);assertThatThrownBy(()->interviews.save(id,input(1,true,"不得代填"))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void submittedEvaluationIsVisibleToRecruitersImmutableAndDoesNotMakeHrDecision() {
        long id=assign();login(interviewer);interviews.save(id,input(0,false,"草稿"));var result=interviews.save(id,input(1,true,"正式评价"));
        assertThat(result.assignment().status()).isEqualTo("SUBMITTED");assertThat(result.assignment().evaluation().submittedAt()).isNotNull();
        assertThatThrownBy(()->interviews.save(id,input(2,false,"不能覆盖"))).isInstanceOf(ResponseStatusException.class);
        login(owner);assertThat(interviews.managed(job).get(0).evaluation().summary()).isEqualTo("正式评价");
        assertThat(db.queryForObject("SELECT hr_decision FROM match_result WHERE resume_id=?",String.class,resume)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_position_activity WHERE position_id=? AND action='INTERVIEW_EVALUATED'",Long.class,job)).isEqualTo(1);
    }
    @Test void staleVersionAndInvalidSubmissionNeverOverwriteDraft() {
        long id=assign();login(interviewer);interviews.save(id,input(0,false,"保留草稿"));
        assertThatThrownBy(()->interviews.save(id,input(0,true,"旧版本覆盖"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->interviews.save(id,new InterviewAssignmentService.EvaluationInput(1,9,"PASS",null,null,"错误评分",true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interviews.save(id,new InterviewAssignmentService.EvaluationInput(1,null,null,null,null,null,true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->interviews.save(id,input(1,true,"x".repeat(3001)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(interviews.detail(id).assignment().evaluation().summary()).isEqualTo("保留草稿");
    }
    @Test void revokeIsImmediateAndReassignmentDoesNotRestoreOldDraft() {
        long id=assign();login(interviewer);interviews.save(id,input(0,false,"旧草稿"));login(owner);interviews.revoke(id);interviews.revoke(id);
        long fresh=interviews.assign(resume,interviewer).id();assertThat(fresh).isNotEqualTo(id);
        login(interviewer);assertThat(interviews.mine()).extracting("id").containsExactly(fresh);
        assertThatThrownBy(()->interviews.detail(id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->interviews.save(id,input(1,true,"不能复活"))).isInstanceOf(ResponseStatusException.class);
        assertThat(interviews.detail(fresh).assignment().evaluation()).isNull();
    }
    @Test void revokedSubmittedResultsRemainReadableOnlyByRecruitmentOperators() {
        long id=assign();login(interviewer);interviews.save(id,input(0,true,"已提交内容"));login(owner);interviews.revoke(id);
        assertThat(interviews.managed(job).get(0).evaluation().summary()).isEqualTo("已提交内容");
        login(interviewer);assertThat(interviews.mine()).isEmpty();assertThatThrownBy(()->interviews.detail(id)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void membershipRoleAndDeletedAccountChangesInvalidateAccess() {
        long id=assign();db.update("UPDATE recruitment_company_member SET role='HR' WHERE user_id=?",interviewer);login(interviewer);
        assertThat(interviews.mine()).isEmpty();assertThatThrownBy(()->interviews.detail(id)).isInstanceOf(ResponseStatusException.class);
        db.update("UPDATE recruitment_company_member SET role='INTERVIEWER' WHERE user_id=?",interviewer);
        db.update("UPDATE sys_user SET deleted=1 WHERE id=?",interviewer);assertThat(interviews.mine()).isEmpty();
        db.update("UPDATE sys_user SET deleted=0 WHERE id=?",interviewer);
        db.update("UPDATE recruitment_company_member SET company_id=? WHERE user_id=?",foreign,owner);assertThat(interviews.mine()).isEmpty();
    }
    @Test void interviewerCannotCreateOrImportJobsOrCallRecruitmentTaskApis() throws Exception {
        assign();login(interviewer);
        call(post("/api/v1/recruitment/positions").contentType("application/json").content("{\"title\":\"forbidden\"}")).andExpect(status().isForbidden());
        call(multipart("/api/v1/recruitment/positions/import").file(new org.springframework.mock.web.MockMultipartFile("file","jd.md","text/plain","fixture".getBytes()))).andExpect(status().isForbidden());
        call(post("/api/v1/recruitment/positions/"+job+"/match/tasks").contentType("application/json").content("{\"requestKey\":\"forbidden-key\"}")).andExpect(status().isNotFound());
        verifyNoInteractions(ai);
    }
    @Test void legacyAssignmentChecksActiveTasksAndConfirmsOnlyExplicitCompanyOwnership() {
        db.update("UPDATE job_position SET company_id=NULL WHERE id=?",job);
        var task=tasks.submit(job,"legacy-interview");
        assertThatThrownBy(()->interviews.assign(resume,interviewer)).hasMessageContaining("进行中任务");
        tasks.cancel(task.id());interviews.assign(resume,interviewer);
        assertThat(db.queryForObject("SELECT company_id FROM job_position WHERE id=?",Long.class,job)).isEqualTo(company);
        assertThat(db.queryForObject("SELECT user_id FROM candidate_resume WHERE id=?",Long.class,resume)).isEqualTo(owner);
    }
    @Test void sourceResumeDeletionCleansAssignmentsAndPrivateEvaluation() {
        long id=assign();login(interviewer);interviews.save(id,input(0,false,"草稿"));login(owner);recruitment.deleteResume(resume);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_interview_assignment WHERE id=?",Long.class,id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_interview_evaluation WHERE assignment_id=?",Long.class,id)).isZero();
        login(interviewer);assertThat(interviews.mine()).isEmpty();
    }
    @Test void httpEvaluationRequiresOwnAssignmentAndAuthentication() throws Exception {
        long id=assign();login(interviewer);String path="/api/v1/interviews/assignments/"+id;
        call(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.candidate.name").value("候选人甲"));
        call(put(path+"/evaluation").contentType("application/json").content("{\"version\":0,\"score\":4,\"recommendation\":\"PASS\",\"summary\":\"HTTP评价\",\"submit\":true,\"interviewerId\":"+second+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignment.status").value("SUBMITTED"));
        login(second);call(get(path)).andExpect(status().isNotFound());
        mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());
    }
}
