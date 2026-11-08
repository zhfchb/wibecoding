package com.example.agent.service;

import com.example.agent.service.company.CompanyService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CompanyServiceTest {
    @Autowired CompanyService companies;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    long user;
    void login(long id) {
        var auth=new UsernamePasswordAuthenticationToken("test",null,List.of());
        auth.setDetails(Map.of("userId",id));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @BeforeEach void setup() {
        db.update("INSERT INTO sys_user (username,password,role) VALUES (?,?,'USER')","company_"+UUID.randomUUID(),passwords.encode("test-password"));
        user=db.queryForObject("SELECT MAX(id) FROM sys_user",Long.class);login(user);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void provisionsIndependentCompanyOnceForExistingAccount() {
        var company=companies.current();assertThat(company.id()).isEqualTo(user);assertThat(company.memberRole()).isEqualTo("ADMIN");
        assertThat(companies.current().id()).isEqualTo(company.id());
        assertThat(companies.members()).hasSize(1);
        assertThat(company.needsCompletion()).isTrue();
    }
    @Test void adminUpdatesCompanyAndCreatesHashedHrAccount() {
        companies.update(new CompanyService.Update("示例公司","上海","简介",Map.of("industry","互联网与软件","scale","21～99人","provinceCode","310000","cityCode","310000")));
        assertThat(companies.current().needsCompletion()).isFalse();
        assertThat(companies.current().profile().get("provinceName")).isEqualTo("上海市");
        var member=companies.addMember(new CompanyService.NewMember("hr_"+user,"initial-password"));
        String hash=db.queryForObject("SELECT password FROM sys_user WHERE id=?",String.class,member.userId());
        assertThat(hash).isNotEqualTo("initial-password");assertThat(passwords.matches("initial-password",hash)).isTrue();
        login(member.userId());assertThat(companies.current().name()).isEqualTo("示例公司");
        assertThat(companies.current().memberRole()).isEqualTo("HR");assertThat(companies.members()).hasSize(2);
    }
    @Test void hrCannotEditCompanyOrAddMembersEvenWithGlobalAdminAuthority() {
        var hr=companies.addMember(new CompanyService.NewMember("hr_"+user,"initial-password"));login(hr.userId());
        var elevated=new UsernamePasswordAuthenticationToken("test",null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        elevated.setDetails(Map.of("userId",hr.userId()));SecurityContextHolder.getContext().setAuthentication(elevated);
        assertThatThrownBy(()->companies.update(new CompanyService.Update("非法改名",null,null))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->companies.addMember(new CompanyService.NewMember("another_hr","initial-password"))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void unrelatedAccountCannotSeeAnotherCompanyOrMembers() {
        var first=companies.current();
        db.update("INSERT INTO sys_user (username,password,role) VALUES (?,?,'USER')","other_"+user,"unused");
        login(db.queryForObject("SELECT MAX(id) FROM sys_user",Long.class));
        assertThat(companies.current().id()).isNotEqualTo(first.id());assertThat(companies.members()).hasSize(1);
    }
    @Test void invalidCompanyNameAndAnonymousAccessAreRejected() {
        assertThatThrownBy(()->companies.update(new CompanyService.Update(" ",null,null))).isInstanceOf(IllegalArgumentException.class);
        SecurityContextHolder.clearContext();assertThatThrownBy(companies::current).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsInvalidProvinceCityAndContactValuesWithoutChangingOldData() {
        var valid=Map.of("industry","互联网与软件","scale","21～99人","provinceCode","310000","cityCode","310000");
        for (var pair:List.of(Map.entry("cityCode","440100"),Map.entry("website","javascript:alert(1)"),
                Map.entry("contactEmail","bad-address"),Map.entry("contactPhone","abcdef"),Map.entry("industry","unknown"))) {
            var profile=new HashMap<>(valid);profile.put(pair.getKey(),pair.getValue());
            assertThatThrownBy(()->companies.update(new CompanyService.Update("新公司",null,"简介",profile)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(companies.current().needsCompletion()).isTrue();
        assertThat(companies.current().name()).isNotEqualTo("新公司");
    }
    @Test void canonicalizesRegionNamesAndCanClearOptionalFields() {
        var profile=new HashMap<>(Map.of("industry","其他","scale","1～20人","provinceCode","310000","cityCode","310000",
                "cityName","伪造名称","website","https://example.com"));
        var result=companies.update(new CompanyService.Update("示例",null,"业务简介",profile));
        assertThat(result.city()).isEqualTo("上海市");
        profile.put("website","");
        assertThat(companies.update(new CompanyService.Update("示例",null,"业务简介",profile)).profile().get("website")).isEmpty();
    }
}
