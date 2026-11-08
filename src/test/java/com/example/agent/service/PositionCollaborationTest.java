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

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class PositionCollaborationTest {
    @Autowired JdbcTemplate db;
    @Autowired RecruitmentService recruitment;
    @Autowired CompanyService companies;
    @Autowired PositionCollaborationService collaboration;
    @Autowired PositionAccess access;
    @Autowired MatchService matches;
    @Autowired InterviewService questions;
    @Autowired ReportService reports;
    @Autowired MockMvc mvc;
    @Autowired com.example.agent.mapper.CandidateResumeMapper resumeMapper;
    @Autowired com.example.agent.service.matching.MatchTaskService matchTasks;
    @Autowired com.example.agent.service.resumes.ResumeParseTaskService parseTasks;
    @Autowired com.example.agent.service.materials.MaterialTaskService materialTasks;
    @MockBean AiGateway ai;
    long owner,reader,colleague,outsider,company,job,resume,match,actor;
    String base;
    long account() {
        String name="collab_"+UUID.randomUUID().toString().substring(0,16);
        db.update("INSERT INTO sys_user(username,password,role) VALUES (?,'unused','USER')",name);
        return db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    void login(long user) {
        actor=user;
        var auth=new UsernamePasswordAuthenticationToken("test",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        auth.setDetails(Map.of("userId",user));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        login(actor);var identity=SecurityContextHolder.getContext().getAuthentication();
        try { return mvc.perform(request.with(authentication(identity))); }
        finally { login(actor); }
    }
    @BeforeEach void setup() {
        owner=account();login(owner);company=companies.current().id();
        reader=companies.addMember(new CompanyService.NewMember("read_"+owner,"initial-password")).userId();
        colleague=companies.addMember(new CompanyService.NewMember("other_"+owner,"initial-password")).userId();
        outsider=account();login(outsider);companies.current();login(owner);
        var input=new PositionRequest();input.setTitle("仅授权岗位");input.setDepartment("旧部门文字");
        job=recruitment.createPosition(input).id();base="/api/v1/recruitment/positions/"+job;
        db.update("INSERT INTO candidate_resume(position_id,user_id,candidate_name,skills,parse_status,raw_text) VALUES (?,?,'授权候选人','[\"Java\"]','PARSED','private original')",job,owner);
        resume=db.queryForObject("SELECT id FROM candidate_resume WHERE position_id=?",Long.class,job);
        db.update("INSERT INTO match_result(position_id,resume_id,match_score,rank_no,hr_decision) VALUES (?,?,88,1,'PENDING')",job,resume);
        match=db.queryForObject("SELECT id FROM match_result WHERE position_id=?",Long.class,job);
        db.update("INSERT INTO interview_question(position_id,resume_id,question) VALUES (?,?,'已有面试问题')",job,resume);
        db.update("INSERT INTO candidate_report(position_id,resume_id,summary,report_md) VALUES (?,?,'已有报告摘要','已有报告正文')",job,resume);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void sharingIsExplicitAndReadOnlyAndDoesNotExposeOtherJobs() {
        var input=new PositionRequest();input.setTitle("未授权岗位");long privateJob=recruitment.createPosition(input).id();
        login(reader);assertThat(recruitment.listPositions()).isEmpty();
        assertThatThrownBy(()->recruitment.getPosition(job)).isInstanceOf(ResponseStatusException.class);
        login(owner);collaboration.grant(job,reader);collaboration.grant(job,reader);
        assertThat(collaboration.list(job)).hasSize(1);
        login(reader);
        assertThat(recruitment.listPositions()).extracting("id").containsExactly(job);
        assertThat(recruitment.getPosition(job).canManage()).isFalse();
        assertThat(recruitment.listResumes(job)).extracting("candidateName").containsExactly("授权候选人");
        assertThat(matches.listMatches(job)).hasSize(1);assertThat(questions.listQuestions(resume)).hasSize(1);
        assertThat(reports.getReport(resume).summary()).isEqualTo("已有报告摘要");assertThat(reports.listReports(job)).hasSize(1);
        assertThatThrownBy(()->recruitment.getPosition(privateJob)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ai);
    }
    @Test void onlyCreatorCanGrantAndForeignTargetsAreRejected() {
        assertThatThrownBy(()->collaboration.grant(job,outsider)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->collaboration.grant(job,owner)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->collaboration.grant(job,null)).isInstanceOf(IllegalArgumentException.class);
        collaboration.grant(job,reader);login(reader);
        assertThatThrownBy(()->collaboration.list(job)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->collaboration.grant(job,colleague)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->collaboration.revoke(job,reader)).isInstanceOf(ResponseStatusException.class);
        login(outsider);assertThatThrownBy(()->access.readable(job)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void revocationImmediatelyDeniesEveryReadFamily() {
        collaboration.grant(job,reader);collaboration.revoke(job,reader);collaboration.revoke(job,reader);login(reader);
        assertThat(recruitment.listPositions()).isEmpty();
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable action:List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                ()->recruitment.getPosition(job),()->recruitment.listResumes(job),()->matches.listMatches(job),
                ()->questions.listQuestions(resume),()->reports.getReport(resume),()->reports.listReports(job)))
            assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class);
        login(owner);assertThat(recruitment.getPosition(job).canManage()).isTrue();
    }
    @Test void changingEitherMembershipInvalidatesExistingGrants() {
        collaboration.grant(job,reader);
        db.update("UPDATE recruitment_company_member SET company_id=? WHERE user_id=?",outsider,reader);login(reader);
        assertThat(recruitment.listPositions()).isEmpty();assertThatThrownBy(()->access.resume(resume)).isInstanceOf(ResponseStatusException.class);
        db.update("UPDATE recruitment_company_member SET company_id=? WHERE user_id=?",company,reader);
        db.update("UPDATE recruitment_company_member SET company_id=? WHERE user_id=?",outsider,owner);
        assertThatThrownBy(()->access.readable(job)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void legacyJobRequiresExplicitGrantAndPreservesOriginalFields() {
        db.update("UPDATE job_position SET company_id=NULL WHERE id=?",job);
        assertThat(recruitment.getPosition(job).companyId()).isNull();
        collaboration.grant(job,reader);
        assertThat(db.queryForObject("SELECT company_id FROM job_position WHERE id=?",Long.class,job)).isEqualTo(company);
        assertThat(db.queryForObject("SELECT department FROM job_position WHERE id=?",String.class,job)).isEqualTo("旧部门文字");
        assertThat(db.queryForObject("SELECT user_id FROM candidate_resume WHERE id=?",Long.class,resume)).isEqualTo(owner);
        assertThat(db.queryForObject("SELECT department_id FROM job_position WHERE id=?",Long.class,job)).isNull();
    }
    @Test void legacyJobCannotChangeCompanyFingerprintDuringActiveTask() {
        db.update("UPDATE job_position SET company_id=NULL WHERE id=?",job);
        db.update("""
            INSERT INTO recruitment_material_task(id,resume_id,owner_id,kind,request_key,input_hash,status,phase,created_at,updated_at,deadline_at)
            VALUES ('collab-active',?,?,'QUESTIONS','fixture-key','hash','RUNNING','GENERATING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,resume,owner);
        assertThatThrownBy(()->collaboration.grant(job,reader)).hasMessageContaining("进行中任务");
        assertThat(db.queryForObject("SELECT company_id FROM job_position WHERE id=?",Long.class,job)).isNull();
        assertThat(collaboration.list(job)).isEmpty();
    }
    @Test void httpReadsWorkButWritesTaskApisAndDelegationAreDenied() throws Exception {
        collaboration.grant(job,reader);login(reader);
        for(String path:List.of(base,base+"/resumes",base+"/match/results",base+"/reports",
                "/api/v1/recruitment/resumes/"+resume+"/questions","/api/v1/recruitment/resumes/"+resume+"/report"))
            call(get(path)).andExpect(status().isOk());
        call(get(base)).andExpect(jsonPath("$.canManage").value(false));
        var writes=List.of(
            delete(base),post(base+"/close"),put(base).contentType("application/json").content("{\"title\":\"不应改名\"}"),
            put(base+"/department").contentType("application/json").content("{\"departmentId\":999}"),
            delete("/api/v1/recruitment/resumes/"+resume),post("/api/v1/recruitment/match/"+match+"/decision?approved=true"),
            multipart(base+"/resumes").file(new org.springframework.mock.web.MockMultipartFile("files","cv.txt","text/plain","fixture".getBytes())),
            post(base+"/match/run"),post(base+"/match/tasks").contentType("application/json").content("{\"requestKey\":\"fixture-key\"}"),
            post(base+"/resumes/parse-tasks").contentType("application/json").content("{\"requestKey\":\"fixture-key\",\"resumeIds\":["+resume+"]}"),
            post("/api/v1/recruitment/resumes/"+resume+"/questions/generate"),post("/api/v1/recruitment/resumes/"+resume+"/report/generate"),
            post("/api/v1/recruitment/resumes/"+resume+"/materials/QUESTIONS/tasks").contentType("application/json").content("{\"requestKey\":\"fixture-key\"}"),
            put(base+"/collaborators").contentType("application/json").content("{\"userId\":"+colleague+"}"),delete(base+"/collaborators/"+reader));
        for(var write:writes) call(write).andExpect(status().is4xxClientError());
        for(String path:List.of(base+"/match/tasks/latest",base+"/resumes/parse-tasks/latest",
                "/api/v1/recruitment/resumes/"+resume+"/materials/QUESTIONS/tasks/latest"))
            call(get(path)).andExpect(status().is4xxClientError());
        assertThat(db.queryForObject("SELECT title FROM job_position WHERE id=?",String.class,job)).isEqualTo("仅授权岗位");
        assertThat(db.queryForObject("SELECT hr_decision FROM match_result WHERE id=?",String.class,match)).isEqualTo("PENDING");
        verifyNoInteractions(ai);
    }
    @Test void grantHttpAndAnonymousBoundaryAndKnowledgeStayScoped() throws Exception {
        call(put(base+"/collaborators").contentType("application/json").content("{\"userId\":"+reader+"}"))
                .andExpect(status().isOk());
        call(get(base+"/collaborators")).andExpect(jsonPath("$[0].userId").value(reader));
        db.update("INSERT INTO document(document_id,file_name,chunk_count,file_size,user_id) VALUES ('collab-private','仅创建人文档',1,1,?)",owner);
        login(reader);
        call(get("/api/v1/knowledge/documents")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        call(post("/internal/v1/retrieve").contentType("application/json").content("{\"ownerId\":"+owner+",\"query\":\"test\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(get(base).with(anonymous())).andExpect(status().isUnauthorized());
    }
    @Test void readersCannotInspectOrCancelCreatorsExistingTasks() throws Exception {
        collaboration.grant(job,reader);
        String matchTask=matchTasks.submit(job,"collab-match-key").id();
        String materialTask=materialTasks.submit(resume,"QUESTIONS","collab-material-key").id();
        var failed=resumeMapper.selectById(resume);failed.setParseStatus("FAILED");resumeMapper.updateById(failed);
        String parseTask=parseTasks.submit(job,"collab-parse-key",List.of(resume)).id();
        login(reader);
        for(String path:List.of("/api/v1/recruitment/match/tasks/"+matchTask,
                "/api/v1/recruitment/resumes/parse-tasks/"+parseTask,"/api/v1/recruitment/materials/tasks/"+materialTask)) {
            call(get(path)).andExpect(status().isNotFound());
            call(post(path+"/cancel")).andExpect(status().isNotFound());
        }
        assertThat(db.queryForObject("SELECT status FROM recruitment_match_task WHERE id=?",String.class,matchTask)).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT status FROM recruitment_resume_parse_task WHERE id=?",String.class,parseTask)).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT status FROM recruitment_material_task WHERE id=?",String.class,materialTask)).isEqualTo("QUEUED");
        verifyNoInteractions(ai);
    }
    @Test void deletingJobAlsoRemovesItsGrants() {
        collaboration.grant(job,reader);recruitment.deletePosition(job);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM recruitment_position_viewer WHERE position_id=?",Long.class,job)).isZero();
        login(reader);assertThat(recruitment.listPositions()).isEmpty();
        assertThatThrownBy(()->recruitment.getPosition(job)).isInstanceOf(ResponseStatusException.class);
    }
}
