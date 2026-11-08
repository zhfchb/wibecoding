package com.example.agent;

import com.example.agent.service.AiGateway;
import com.example.agent.service.ResumeParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real TCP/HTTP + JWT + multipart journey. H2/hash vectors; only AI extraction is simulated. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="spring.datasource.url=jdbc:h2:mem:recruitment_journey;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RecruitmentJourneyHttpTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @MockBean ResumeParser parser;
    @MockBean AiGateway ai;
    @MockBean Clock clock;
    JsonNode request(String token,HttpMethod method,String path,Object body,int status) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) headers.setBearerAuth(token);
        var response=http.exchange("/api/v1"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).as("%s %s",method,path).isEqualTo(status);
        return response.getBody()==null?json.nullNode():response.getBody();
    }
    JsonNode get(String token,String path) {return request(token,HttpMethod.GET,path,null,200);}
    JsonNode post(String token,String path,Object body) {return request(token,HttpMethod.POST,path,body,200);}
    String register(String name) {return post(null,"/auth/register",Map.of("username",name,"password","fixture-password")).path("token").asText();}
    String login(String name) {return post(null,"/auth/login",Map.of("username",name,"password","fixture-password")).path("token").asText();}
    @Test void recruitmentJourneyWithRealHttpAuthenticationAndRoleBoundaries() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        when(parser.parse(anyString())).thenReturn(json.readTree("""
            {"candidate_name":"验收候选人","skills":["Java"],"years_exp":4,"education":"本科"}
            """));
        String owner=register("journey_owner");get(owner,"/company");
        long editorId=post(owner,"/company/members",Map.of("username","journey_editor","password","fixture-password","role","HR")).path("userId").asLong();
        long viewerId=post(owner,"/company/members",Map.of("username","journey_viewer","password","fixture-password","role","HR")).path("userId").asLong();
        long interviewerId=post(owner,"/company/members",Map.of("username","journey_interviewer","password","fixture-password","role","INTERVIEWER")).path("userId").asLong();
        String editor=login("journey_editor"),viewer=login("journey_viewer"),interviewer=login("journey_interviewer"),outsider=register("journey_outsider");
        long department=post(owner,"/company/departments",Map.of("name","验收研发部")).path("id").asLong();
        long job=post(owner,"/recruitment/positions",Map.of("title","验收Java岗位","departmentId",department,"requiredSkills",List.of("Java"))).path("id").asLong();
        String jobPath="/recruitment/positions/"+job;
        request(owner,HttpMethod.PUT,jobPath+"/collaborators",Map.of("userId",editorId,"permission","EDITOR"),200);
        request(owner,HttpMethod.PUT,jobPath+"/collaborators",Map.of("userId",viewerId,"permission","VIEWER"),200);

        var file=new ByteArrayResource("验收候选人，Java开发4年，本科。".getBytes(StandardCharsets.UTF_8)) { @Override public String getFilename(){return "journey.md";} };
        var upload=new LinkedMultiValueMap<String,Object>();upload.add("files",file);
        var headers=new HttpHeaders();headers.setBearerAuth(owner);headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response=http.postForEntity("/api/v1"+jobPath+"/resumes",new HttpEntity<>(upload,headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("successCount").asInt()).isEqualTo(1);
        var resumes=get(editor,jobPath+"/resumes");long resume=resumes.get(0).path("id").asLong();
        assertThat(resumes.get(0).path("parseStatus").asText()).isEqualTo("PARSED");
        String outcome="/recruitment/resumes/"+resume+"/outcome";
        assertThat(get(viewer,jobPath+"/progress").get(0).path("fileName").asText()).isEqualTo("journey.md");
        for(String token:List.of(interviewer,outsider)) request(token,HttpMethod.GET,jobPath+"/progress",null,404);
        request(null,HttpMethod.GET,jobPath+"/progress",null,401);
        request(viewer,HttpMethod.POST,outcome,Map.of("version",0,"action","DECIDE","outcome","HIRED","reason","越权"),404);

        long assignment=post(editor,"/recruitment/resumes/"+resume+"/interviews",Map.of("interviewerId",interviewerId)).path("id").asLong();
        String schedule="/recruitment/interviews/"+assignment+"/schedule";
        request(editor,HttpMethod.PUT,schedule,Map.of("version",0,"localStart","2030-01-02T10:00:00","localEnd","2030-01-02T11:00:00","timeZone","Asia/Shanghai","mode","VIDEO","location","验收会议"),200);
        String evaluation="/interviews/assignments/"+assignment+"/evaluation";
        request(interviewer,HttpMethod.PUT,evaluation,Map.of("version",0,"score",4,"recommendation","PASS","summary","草稿不应泄漏","submit",false),200);
        assertThat(get(editor,jobPath+"/interviews").get(0).path("evaluation").isNull()).isTrue();
        assertThat(get(viewer,jobPath+"/progress").get(0).path("submittedEvaluations").asInt()).isZero();
        request(interviewer,HttpMethod.PUT,evaluation,Map.of("version",1,"score",4,"recommendation","PASS","summary","提前提交","submit",true),409);
        when(clock.instant()).thenReturn(Instant.parse("2030-01-03T00:00:00Z"));
        request(interviewer,HttpMethod.PUT,evaluation,Map.of("version",1,"score",4,"recommendation","PASS","summary","人工确认完成面试","submit",true),200);
        var progress=get(viewer,jobPath+"/progress").get(0);
        assertThat(progress.path("submittedEvaluations").asInt()).isEqualTo(1);
        assertThat(progress.path("outcome").asText()).isEqualTo("PENDING");
        assertThat(get(editor,jobPath+"/interviews").get(0).path("schedule").path("status").asText()).isEqualTo("COMPLETED");

        post(editor,outcome,Map.of("version",0,"action","DECIDE","outcome","HIRED","reason","HR结合证据确认"));
        var stale=request(owner,HttpMethod.POST,outcome,Map.of("version",0,"action","DECIDE","outcome","REJECTED","reason","旧版本"),409);
        assertThat(stale.path("error").asText()).isEqualTo("OUTCOME_VERSION");
        post(owner,outcome,Map.of("version",1,"action","ARCHIVE","reason","招聘结束"));
        assertThat(get(viewer,jobPath+"/progress").get(0).path("archived").asBoolean()).isTrue();
        post(editor,outcome,Map.of("version",2,"action","REOPEN","reason","复核"));
        assertThat(get(viewer,outcome+"/history")).hasSize(3);
        request(owner,HttpMethod.DELETE,jobPath+"/collaborators/"+editorId,null,200);
        request(editor,HttpMethod.GET,jobPath+"/progress",null,404);
        request(editor,HttpMethod.POST,outcome,Map.of("version",3,"action","ARCHIVE","reason","已撤权"),404);
        request(owner,HttpMethod.DELETE,"/recruitment/resumes/"+resume,null,200);
        assertThat(get(viewer,jobPath+"/progress")).isEmpty();
        request(interviewer,HttpMethod.GET,"/interviews/assignments/"+assignment,null,404);
        request(owner,HttpMethod.GET,outcome+"/history",null,404);
        verify(parser).parse(anyString());verifyNoInteractions(ai);
    }
}
