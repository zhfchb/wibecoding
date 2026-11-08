package com.example.agent.service;

import com.example.agent.service.company.CompanyService;
import com.example.agent.service.company.DepartmentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class DepartmentServiceTest {
    @Autowired DepartmentService departments;
    @Autowired CompanyService companies;
    @Autowired JdbcTemplate db;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    final List<Long> accounts=new ArrayList<>();
    final List<Long> companyIds=new ArrayList<>();
    long owner;
    void login(long id,boolean systemAdmin) {
        var auth=new UsernamePasswordAuthenticationToken("test",null,systemAdmin
                ? List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")) : List.of());
        auth.setDetails(Map.of("userId",id));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    long account() {
        String username="dept_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        db.update("INSERT INTO sys_user (username,password,role) VALUES (?,'unused','USER')",username);
        long id=db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,username);accounts.add(id);return id;
    }
    @BeforeEach void setup() {
        owner=account();login(owner,false);companyIds.add(companies.current().id());
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        for (long id:companyIds) {
            db.update("DELETE FROM recruitment_department WHERE company_id=?",id);
            db.update("DELETE FROM recruitment_company_member WHERE company_id=?",id);
            db.update("DELETE FROM recruitment_company WHERE id=?",id);
        }
        for (long id:accounts) db.update("DELETE FROM sys_user WHERE id=?",id);
    }
    @Test void adminCreatesEditsDisablesAndReenablesWithoutDeleting() {
        var department=departments.create(new DepartmentService.Input(" 研发部 ","业务研发"));
        assertThat(department.name()).isEqualTo("研发部");assertThat(department.status()).isEqualTo("ACTIVE");
        assertThat(departments.update(department.id(),new DepartmentService.Input("平台部",null)).description()).isNull();
        departments.status(department.id(),false);
        assertThat(departments.list(true)).isEmpty();assertThat(departments.list(false)).hasSize(1);
        assertThat(departments.status(department.id(),true).id()).isEqualTo(department.id());
        assertThat(departments.list(true)).hasSize(1);
    }
    @Test void duplicateNamesIncludeDisabledDepartmentsAndNormalizedNames() {
        var original=departments.create(new DepartmentService.Input("Dev",null));
        departments.status(original.id(),false);
        assertThatThrownBy(()->departments.create(new DepartmentService.Input(" Ｄｅｖ ",null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同名");
        var second=departments.create(new DepartmentService.Input("Other",null));
        assertThatThrownBy(()->departments.update(second.id(),new DepartmentService.Input("dev",null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(departments.list(false)).extracting(DepartmentService.Department::name).containsExactly("Dev","Other");
    }
    @Test void anotherCompanyCannotReadOrMutateForeignDepartment() {
        var original=departments.create(new DepartmentService.Input("研发部",null));
        long other=account();login(other,false);companyIds.add(companies.current().id());
        assertThat(departments.list(false)).isEmpty();
        assertThatThrownBy(()->departments.update(original.id(),new DepartmentService.Input("非法改名",null)))
                .isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(()->departments.status(original.id(),false)).isInstanceOf(ResponseStatusException.class);
        assertThat(departments.create(new DepartmentService.Input("研发部",null)).id()).isNotEqualTo(original.id());
    }
    @Test void hrCanReadButSystemAdminClaimDoesNotGrantCompanyWritePermission() {
        var original=departments.create(new DepartmentService.Input("研发部",null));
        var hr=companies.addMember(new CompanyService.NewMember("dept_hr_"+owner,"initial-password"));accounts.add(hr.userId());
        login(hr.userId(),true);
        assertThat(departments.list(false)).hasSize(1);
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable action:List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                ()->departments.create(new DepartmentService.Input("另一个",null)),
                ()->departments.update(original.id(),new DepartmentService.Input("非法改名",null)),
                ()->departments.status(original.id(),false)))
            assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
                    .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(403));
    }
    @Test void invalidValuesAndAnonymousAccessNeverCreateRows() {
        for (String name:List.of(" ","　","x".repeat(101)))
            assertThatThrownBy(()->departments.create(new DepartmentService.Input(name,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->departments.create(new DepartmentService.Input("研发部","x".repeat(501)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(departments.list(false)).isEmpty();
        SecurityContextHolder.clearContext();assertThatThrownBy(()->departments.list(false)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void concurrentDuplicateCreatesOneRecord() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var attempts=new ArrayList<Future<Boolean>>();
            for (int i=0;i<2;i++) attempts.add(pool.submit(()->{
                login(owner,false);
                try { gate.await();departments.create(new DepartmentService.Input("同名部门",null));return true; }
                catch (IllegalArgumentException duplicate) { return false; }
                finally { SecurityContextHolder.clearContext(); }
            }));
            gate.countDown();int succeeded=0;for(var attempt:attempts) if(attempt.get(10,TimeUnit.SECONDS)) succeeded++;
            assertThat(succeeded).isEqualTo(1);assertThat(departments.list(false)).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void httpEndpointsRequireAuthenticationAndPersistOnlyInCurrentCompany() throws Exception {
        var identity=SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/company/departments")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(identity))
                .contentType("application/json").content("{\"name\":\"HTTP部门\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ACTIVE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/company/departments")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(identity)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].name").value("HTTP部门"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/company/departments")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
}
