package com.example.agent.service;

import com.example.agent.domain.recruitment.PositionRequest;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.service.company.CompanyService;
import com.example.agent.service.company.DepartmentService;
import com.example.agent.service.company.PositionDepartmentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PositionDepartmentTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired RecruitmentService recruitment;
    @Autowired DepartmentService departments;
    @Autowired PositionDepartmentService bindings;
    @Autowired CompanyService companies;
    @Autowired JobPositionMapper positions;

    @Test void staleEditDoesNotReopenClosedPosition() {
        var input=new PositionRequest();input.setTitle("initial");var created=recruitment.createPosition(input);
        var stale=positions.selectById(created.id());stale.setTitle("updated title");
        recruitment.closePosition(created.id());
        bindings.update(stale,null);
        assertThat(positions.selectById(created.id()).getStatus()).isEqualTo("CLOSED");
        assertThat(positions.selectById(created.id()).getTitle()).isEqualTo("updated title");
    }
    @Autowired JdbcTemplate db;
    @MockBean AiGateway ai;
    @MockBean KnowledgeService knowledge;
    long owner,company;

    long account() {
        String username="posdept_"+UUID.randomUUID().toString().substring(0,16);
        db.update("INSERT INTO sys_user(username,password,role) VALUES (?,'unused','USER')",username);
        return db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);
    }
    void login(long id) {
        var auth=new UsernamePasswordAuthenticationToken("test",null,List.of());
        auth.setDetails(Map.of("userId",id));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @BeforeEach void setup() { owner=account();login(owner);company=companies.current().id(); }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    PositionRequest request(Long department) {
        var request=new PositionRequest();request.setTitle("平台开发");request.setDepartmentId(department);return request;
    }
    long department(String name) { return departments.create(new DepartmentService.Input(name,null)).id(); }

    @Test void usesVerifiedCompanyAndDepartmentInsteadOfClientText() {
        long dep=department("研发部");var input=request(dep);input.setDepartment("伪造部门");
        var created=recruitment.createPosition(input);
        assertThat(created.companyId()).isEqualTo(company);
        assertThat(created.departmentId()).isEqualTo(dep);
        assertThat(created.department()).isEqualTo("研发部");
        assertThat(positions.selectById(created.id()).getUserId()).isEqualTo(owner);
        assertThat(recruitment.listPositions()).extracting("departmentId").containsExactly(dep);
    }

    @Test void noDepartmentRemainsAllowedForApiAndImportedText() {
        var input=request(null);input.setDepartment("JD原始部门");
        var created=recruitment.createPosition(input);
        assertThat(created.departmentId()).isNull();assertThat(created.department()).isEqualTo("JD原始部门");
        assertThat(created.companyId()).isEqualTo(company);
    }

    @Test void refusesForeignDisabledAndUnknownDepartmentsWithoutCreatingJobs() {
        long disabled=department("停用部");departments.status(disabled,false);
        long other=account();login(other);long foreign=department("另一公司");login(owner);
        for (long id:List.of(disabled,foreign,Long.MAX_VALUE))
            assertThatThrownBy(()->recruitment.createPosition(request(id))).isInstanceOf(IllegalArgumentException.class);
        assertThat(recruitment.listPositions()).isEmpty();
    }

    @Test void preservesLegacyRowsUntilExplicitReviewedAssociation() {
        db.update("INSERT INTO job_position(user_id,title,department,status) VALUES (?,'历史岗位','历史文本','OPEN')",owner);
        long id=db.queryForObject("SELECT id FROM job_position WHERE user_id=?",Long.class,owner);
        var legacy=recruitment.getPosition(id);
        assertThat(legacy.department()).isEqualTo("历史文本");assertThat(legacy.companyId()).isNull();
        assertThat(positions.selectById(id).getDepartmentId()).isNull();
        long dep=department("正式研发部");bindings.bind(id,dep);
        var linked=recruitment.getPosition(id);
        assertThat(linked.departmentId()).isEqualTo(dep);assertThat(linked.companyId()).isEqualTo(company);
        assertThat(linked.title()).isEqualTo("历史岗位");
    }

    @Test void renameAndDisableRemainReadableAndUnrelatedUpdatePreservesAssociation() {
        long dep=department("研发部");var created=recruitment.createPosition(request(dep));
        departments.update(dep,new DepartmentService.Input("平台研发部",null));departments.status(dep,false);
        assertThat(recruitment.getPosition(created.id()).department()).isEqualTo("平台研发部");
        assertThat(recruitment.getPosition(created.id()).departmentStatus()).isEqualTo("DISABLED");
        var input=request(null);input.setTitle("更新岗位名称");input.setDepartment("不能覆盖关联");
        var updated=recruitment.updatePosition(created.id(),input);
        assertThat(updated.departmentId()).isEqualTo(dep);assertThat(updated.department()).isEqualTo("平台研发部");
        assertThatThrownBy(()->bindings.bind(created.id(),dep)).hasMessageContaining("停用");
        long active=department("测试部");bindings.bind(created.id(),active);
        assertThat(recruitment.getPosition(created.id()).departmentId()).isEqualTo(active);
    }

    @Test void hrCanAssociateOwnJobButCannotAccessColleaguesJob() {
        long dep=department("研发部");var adminJob=recruitment.createPosition(request(dep));
        var member=companies.addMember(new CompanyService.NewMember("hr_"+owner,"initial-password"));login(member.userId());
        var hrJob=recruitment.createPosition(request(dep));assertThat(hrJob.companyId()).isEqualTo(company);
        assertThat(recruitment.listPositions()).extracting("id").containsExactly(hrJob.id());
        assertThatThrownBy(()->bindings.bind(adminJob.id(),dep)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->recruitment.getPosition(adminJob.id())).isInstanceOf(ResponseStatusException.class);
        bindings.bind(hrJob.id(),dep);
    }

    @Test void rejectsUnauthorizedBindingAndLeavesLegacyTextUntouched() {
        var input=request(null);input.setDepartment("旧文本");var created=recruitment.createPosition(input);
        long dep=department("有效部门");long other=account();login(other);companies.current();
        assertThatThrownBy(()->bindings.bind(created.id(),dep)).isInstanceOf(ResponseStatusException.class);
        login(owner);
        assertThatThrownBy(()->bindings.bind(created.id(),null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(recruitment.getPosition(created.id()).department()).isEqualTo("旧文本");
        assertThat(recruitment.getPosition(created.id()).departmentId()).isNull();
    }

    @Test void importingJdNeverTrustsModelDepartmentIdsOrAutoMatchesText() {
        department("研发部");
        when(ai.structured(eq("position"),anyString())).thenReturn("{\"title\":\"导入岗位\",\"department\":\"研发部\",\"departmentId\":999,\"companyId\":999}");
        when(knowledge.upload(any())).thenReturn(new com.example.agent.domain.knowledge.KnowledgeUploadResponse("fixture-doc","jd.md",1));
        var created=recruitment.importPosition(new MockMultipartFile("file","jd.md","text/plain","JD fixture".getBytes()));
        assertThat(created.companyId()).isEqualTo(company);assertThat(created.departmentId()).isNull();
        assertThat(created.department()).isEqualTo("研发部");assertThat(positions.selectById(created.id()).getDocId()).isEqualTo("fixture-doc");
    }

    @Test void httpBindingRequiresAuthenticationAndRejectsOtherOwners() throws Exception {
        var created=recruitment.createPosition(request(null));long dep=department("HTTP部门");
        var identity=SecurityContextHolder.getContext().getAuthentication();
        String path="/api/v1/recruitment/positions/"+created.id()+"/department";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(identity))
                .contentType("application/json").content("{\"departmentId\":"+dep+",\"companyId\":999}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.departmentId").value(dep))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.companyId").value(company));
        login(account());var stranger=SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(stranger))
                .contentType("application/json").content("{\"departmentId\":"+dep+"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())
                .contentType("application/json").content("{\"departmentId\":"+dep+"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
}
