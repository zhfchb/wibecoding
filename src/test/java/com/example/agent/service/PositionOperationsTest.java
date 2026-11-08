package com.example.agent.service;

import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.service.company.*;
import com.example.agent.service.matching.*;
import com.example.agent.service.materials.*;
import com.example.agent.service.resumes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PositionOperationsTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired CompanyService companies;
    @Autowired PositionCollaborationService grants;
    @Autowired TaskAuthority authority;
    @Autowired RecruitmentService recruitment;
    @Autowired MatchService matches;
    @Autowired MatchTaskService matchTasks;
    @Autowired MaterialTaskService materials;
    @Autowired ResumeParseTaskService parses;
    @Autowired CandidateResumeMapper resumes;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean AiGateway ai;
    @MockBean ResumeParser parser;
    long owner,editor,other,viewer,company,job,resume,match;
    final List<Long> users=new ArrayList<>();
    void login(long id) {
        var identity=new UsernamePasswordAuthenticationToken("fixture",null,List.of());
        identity.setDetails(Map.of("userId",id));SecurityContextHolder.getContext().setAuthentication(identity);
    }
    @BeforeEach void setup() throws Exception {
        String username="ops_"+UUID.randomUUID().toString().substring(0,18);
        db.update("INSERT INTO sys_user(username,password,role) VALUES (?,'unused','USER')",username);
        owner=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);users.add(owner);login(owner);
        company=companies.current().id();
        editor=member("ed_");other=member("other_");viewer=member("view_");
        var input=new PositionRequest();input.setTitle("协作测试岗位");input.setRequiredSkills(List.of("Java"));
        job=recruitment.createPosition(input).id();
        grants.grant(job,editor,"EDITOR");grants.grant(job,other,"EDITOR");grants.grant(job,viewer);
        db.update("INSERT INTO candidate_resume(position_id,user_id,candidate_name,skills,parse_status,raw_text,doc_id) VALUES (?,?,'原候选人','[\"Java\"]','PARSED','Java original','original-doc')",job,owner);
        resume=db.queryForObject("SELECT id FROM candidate_resume WHERE position_id=?",Long.class,job);
        db.update("INSERT INTO match_result(position_id,resume_id,match_score,rank_no,hr_decision) VALUES (?,?,88,1,'PENDING')",job,resume);
        match=db.queryForObject("SELECT id FROM match_result WHERE position_id=?",Long.class,job);
        db.update("INSERT INTO interview_question(position_id,resume_id,question) VALUES (?,?,'原面试题')",job,resume);
        db.update("INSERT INTO candidate_report(position_id,resume_id,summary,report_md) VALUES (?,?,'原报告','原报告正文')",job,resume);
        when(ai.structured(anyString(),anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return output(call.getArgument(0));
        });
        when(parser.parse(anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return json.readTree("{\"candidate_name\":\"新候选人\",\"skills\":[\"Java\"],\"years_exp\":3}");
        });
    }
    long member(String prefix) {
        long id=companies.addMember(new CompanyService.NewMember(prefix+owner,"fixture-password")).userId();users.add(id);return id;
    }
    String output(String kind) throws Exception {
        if("match".equals(kind)) return "[{\"resumeId\":"+resume+",\"score\":80,\"hits\":[\"Java\"]}]";
        if("interview".equals(kind)) {
            var rows=new ArrayList<Object>();for(int i=0;i<6;i++) rows.add(Map.of("category",i<3?"技术":i<5?"项目":"行为","question","新问题"+i,"assessPoint","考察","referenceAnswer","答案","difficulty","MEDIUM"));
            return json.writeValueAsString(rows);
        }
        return "{\"summary\":\"新报告\",\"reportMd\":\"新报告正文\",\"finalRecommend\":\"YES\"}";
    }
    void state(String status) { var row=resumes.selectById(resume);row.setParseStatus(status);resumes.updateById(row); }
    void revoke() { login(owner);grants.revoke(job,editor);SecurityContextHolder.clearContext(); }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        db.update("DELETE FROM recruitment_material_request WHERE resume_id=?",resume);
        db.update("DELETE FROM recruitment_material_task WHERE resume_id=?",resume);
        db.update("DELETE FROM recruitment_resume_parse_item WHERE task_id IN (SELECT id FROM recruitment_resume_parse_task WHERE position_id=?)",job);
        db.update("DELETE FROM recruitment_resume_parse_task WHERE position_id=?",job);
        db.update("DELETE FROM recruitment_match_request WHERE position_id=?",job);
        db.update("DELETE FROM recruitment_match_task WHERE position_id=?",job);
        db.update("DELETE FROM recruitment_task_actor WHERE position_id=?",job);db.update("DELETE FROM recruitment_position_activity WHERE position_id=?",job);
        for(String table:List.of("interview_question","candidate_report","match_result","candidate_resume","recruitment_position_viewer")) db.update("DELETE FROM "+table+" WHERE position_id=?",job);
        db.update("DELETE FROM job_position WHERE id=?",job);db.update("DELETE FROM recruitment_company_member WHERE company_id=?",company);db.update("DELETE FROM recruitment_company WHERE id=?",company);
        for(Long id:users) db.update("DELETE FROM sys_user WHERE id=?",id);
    }
    @Test void editorGetsOperationsButNotOwnershipOrDelegationAndDecisionsAreAttributed() {
        login(editor);var position=recruitment.getPosition(job);assertThat(position.canOperate()).isTrue();assertThat(position.canManage()).isFalse();
        matches.decide(match,true);matches.decide(match,true);
        assertThat(authority.activity(job)).filteredOn(a->a.action().equals("CANDIDATE_APPROVED")).hasSize(1).allSatisfy(a->assertThat(a.actorId()).isEqualTo(editor));
        assertThatThrownBy(()->recruitment.closePosition(job)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->grants.grant(job,viewer,"EDITOR")).isInstanceOf(ResponseStatusException.class);
        login(viewer);assertThat(recruitment.getPosition(job).canOperate()).isFalse();
        assertThatThrownBy(()->matches.decide(match,false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->authority.activity(job)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void editorMatchTracksActorButKeepsDataOwnerAndIdempotentOriginalInitiator() {
        login(editor);var submitted=matchTasks.submit(job,"operations-match");assertThat(submitted.actorId()).isEqualTo(editor);assertThat(submitted.canCancel()).isTrue();
        login(other);var reused=matchTasks.submit(job,"operations-alias");assertThat(reused.id()).isEqualTo(submitted.id());assertThat(reused.actorId()).isEqualTo(editor);assertThat(reused.canCancel()).isFalse();
        SecurityContextHolder.clearContext();matchTasks.execute(submitted.id(),true);
        login(owner);assertThat(matchTasks.get(submitted.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT owner_id FROM recruitment_match_task WHERE id=?",Long.class,submitted.id())).isEqualTo(owner);
        assertThat(db.queryForObject("SELECT user_id FROM candidate_resume WHERE id=?",Long.class,resume)).isEqualTo(owner);
        assertThat(authority.activity(job)).filteredOn(a->a.action().equals("MATCH_SUBMITTED")).hasSize(1);
    }
    @Test void editorCanReparseWithoutChangingResumeOrDocumentOwnership() {
        state("FAILED");login(editor);var task=parses.submit(job,"operations-parse",List.of(resume));
        assertThat(parses.submit(job,"operations-parse",List.of(resume)).actorId()).isEqualTo(editor);
        SecurityContextHolder.clearContext();parses.execute(task.id());login(owner);
        assertThat(parses.get(task.id()).status()).isEqualTo("SUCCEEDED");assertThat(resumes.selectById(resume).getUserId()).isEqualTo(owner);
        assertThat(resumes.selectById(resume).getDocId()).isEqualTo("original-doc");
    }
    @ParameterizedTest @ValueSource(strings={"QUESTIONS","REPORT"})
    void editorCanGenerateArtifacts(String kind) {
        login(editor);var task=materials.submit(resume,kind,"operations-material");assertThat(task.actorId()).isEqualTo(editor);
        SecurityContextHolder.clearContext();materials.execute(task.id(),true);login(owner);
        assertThat(materials.get(task.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT owner_id FROM recruitment_material_task WHERE id=?",Long.class,task.id())).isEqualTo(owner);
    }
    @Test void cancellationIsLimitedToCreatorOrInitiatorForAllTaskKinds() {
        login(editor);var mt=matchTasks.submit(job,"cancel-match-key");var art=materials.submit(resume,"QUESTIONS","cancel-material");state("FAILED");var parse=parses.submit(job,"cancel-parse-key",List.of(resume));
        login(other);assertThat(matchTasks.get(mt.id()).canCancel()).isFalse();assertThat(materials.get(art.id()).canCancel()).isFalse();assertThat(parses.get(parse.id()).canCancel()).isFalse();
        assertThatThrownBy(()->matchTasks.cancel(mt.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->materials.cancel(art.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->parses.cancel(parse.id())).isInstanceOf(ResponseStatusException.class);
        login(editor);assertThat(matchTasks.cancel(mt.id()).status()).isEqualTo("CANCELLED");assertThat(parses.cancel(parse.id()).status()).isEqualTo("CANCELLED");
        login(owner);assertThat(materials.cancel(art.id()).status()).isEqualTo("CANCELLED");materials.cancel(art.id());
        assertThat(authority.activity(job)).filteredOn(a->a.action().equals("MATERIAL_CANCELLED")).hasSize(1);
    }
    @Test void queuedTasksFailAfterDowngradeBeforeAnyModelCall() {
        login(editor);var mt=matchTasks.submit(job,"queued-match-key");var art=materials.submit(resume,"QUESTIONS","queued-material");state("FAILED");var pt=parses.submit(job,"queued-parse-key",List.of(resume));
        login(owner);grants.grant(job,editor,"VIEWER");SecurityContextHolder.clearContext();matchTasks.execute(mt.id(),true);parses.execute(pt.id());state("PARSED");materials.execute(art.id(),true);
        for(String[] task:List.of(new String[]{"recruitment_match_task",mt.id()},new String[]{"recruitment_material_task",art.id()},new String[]{"recruitment_resume_parse_task",pt.id()}))
            assertThat(db.queryForObject("SELECT error_code FROM "+task[0]+" WHERE id=?",String.class,task[1])).isEqualTo("AUTH_REVOKED");
        verifyNoInteractions(ai,parser);
    }
    @Test void revocationDuringMatchDoesNotOverwriteScores() throws Exception {
        login(editor);var task=matchTasks.submit(job,"inflight-match");
        when(ai.structured(eq("match"),anyString())).thenAnswer(call->{revoke();return output("match");});
        SecurityContextHolder.clearContext();matchTasks.execute(task.id(),true);login(owner);
        assertThat(matchTasks.get(task.id()).errorCode()).isEqualTo("AUTH_REVOKED");
        assertThat(db.queryForObject("SELECT match_score FROM match_result WHERE id=?",Integer.class,match)).isEqualTo(88);
    }
    @ParameterizedTest @ValueSource(strings={"QUESTIONS","REPORT"})
    void revocationDuringArtifactGenerationKeepsOldContent(String kind) throws Exception {
        login(editor);var task=materials.submit(resume,kind,"inflight-material");
        String model="QUESTIONS".equals(kind)?"interview":"report";
        when(ai.structured(eq(model),anyString())).thenAnswer(call->{revoke();return output(model);});
        SecurityContextHolder.clearContext();materials.execute(task.id(),true);login(owner);
        assertThat(materials.get(task.id()).errorCode()).isEqualTo("AUTH_REVOKED");
        assertThat(db.queryForObject("SELECT question FROM interview_question WHERE resume_id=?",String.class,resume)).isEqualTo("原面试题");
        assertThat(db.queryForObject("SELECT summary FROM candidate_report WHERE resume_id=?",String.class,resume)).isEqualTo("原报告");
    }
    @Test void revocationDuringReparsePreservesOriginalProfile() throws Exception {
        state("FAILED");login(editor);var task=parses.submit(job,"inflight-parse",List.of(resume));
        when(parser.parse(anyString())).thenAnswer(call->{revoke();return json.readTree("{\"candidate_name\":\"不得覆盖\",\"skills\":[\"Java\"]}");});
        SecurityContextHolder.clearContext();parses.execute(task.id());login(owner);
        assertThat(parses.get(task.id()).errorCode()).isEqualTo("AUTH_REVOKED");assertThat(resumes.selectById(resume).getCandidateName()).isEqualTo("原候选人");
    }
    @Test void regrantDoesNotResurrectOldTasksButFreshSubmissionWorks() {
        login(editor);var task=matchTasks.submit(job,"old-grant-match");
        login(owner);grants.revoke(job,editor);grants.grant(job,editor,"EDITOR");SecurityContextHolder.clearContext();matchTasks.execute(task.id(),true);
        login(editor);assertThat(matchTasks.get(task.id()).errorCode()).isEqualTo("AUTH_REVOKED");
        var fresh=matchTasks.submit(job,"new-grant-match");SecurityContextHolder.clearContext();matchTasks.execute(fresh.id(),true);login(owner);
        assertThat(matchTasks.get(fresh.id()).status()).isEqualTo("SUCCEEDED");
    }
    @Test void httpOperatorCannotForgeActorOrOwnerAndCannotUploadDeleteOrDelegate() throws Exception {
        login(editor);var identity=SecurityContextHolder.getContext().getAuthentication();
        String base="/api/v1/recruitment/positions/"+job;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base+"/match/tasks")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(identity))
                .contentType("application/json").content("{\"requestKey\":\"http-editor-match\",\"actorId\":"+owner+",\"ownerId\":"+editor+"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.actorId").value(editor));
        var requests=List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base+"/close"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base).contentType("application/json").content("{\"title\":\"forbidden\"}"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base+"/department").contentType("application/json").content("{\"departmentId\":999}"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base+"/collaborators").contentType("application/json").content("{\"userId\":"+viewer+",\"permission\":\"EDITOR\"}"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(base+"/resumes").file(new org.springframework.mock.web.MockMultipartFile("files","cv.txt","text/plain","fixture".getBytes())));
        for(var request:requests) mvc.perform(request.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(identity)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is4xxClientError());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+"/activity")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(identity)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].actorId").value(editor));
        assertThat(db.queryForObject("SELECT owner_id FROM recruitment_match_task WHERE position_id=?",Long.class,job)).isEqualTo(owner);
    }
}
