package com.example.agent.service.company;

import com.example.agent.mapper.UserMapper;
import com.example.agent.entity.UserEntity;
import com.example.agent.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyService {
    private final JdbcTemplate db;
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final CompanyProfileCatalog catalog;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public record Company(Long id,String name,String city,String description,String memberRole,java.util.Map<String,String> profile,boolean needsCompletion) {}
    public record Update(String name,String city,String description,java.util.Map<String,String> profile) {
        public Update(String name,String city,String description) { this(name,city,description,null); }
    }
    public java.util.Map<String,Object> options() { return catalog.options(); }
    public record Member(Long userId,String username,String role) {}
    public record NewMember(String username,String password,String role) {
        public NewMember(String username,String password) { this(username,password,"HR"); }
    }

    /** Provision per user, under a user-row lock. Existing accounts are never silently merged. */
    @Transactional
    public Company current() {
        Long id=SecurityUtils.getCurrentUserId();
        if (id==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var account=db.query("SELECT username FROM sys_user WHERE id=? AND deleted=0 FOR UPDATE",(r,n)->r.getString(1),id);
        if (account.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var memberships=db.query("SELECT company_id FROM recruitment_company_member WHERE user_id=?",(r,n)->r.getLong(1),id);
        if (memberships.isEmpty()) {
            db.update("INSERT INTO recruitment_company (id,name) VALUES (?,?)",id,account.get(0)+"的公司");
            db.update("INSERT INTO recruitment_company_member (user_id,company_id,role) VALUES (?,?,'ADMIN')",id,id);
        }
        return db.queryForObject("SELECT c.*,m.role FROM recruitment_company c JOIN recruitment_company_member m ON c.id=m.company_id WHERE m.user_id=?",
                (r,n)->{
                    java.util.Map<String,String> profile;
                    try { profile=r.getString("profile_json")==null ? java.util.Map.of() : json.readValue(r.getString("profile_json"),new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,String>>(){}); }
                    catch (Exception e) { profile=java.util.Map.of(); }
                    boolean incomplete=false;
                    try { catalog.validate(profile); } catch (IllegalArgumentException e) { incomplete=true; }
                    String description=r.getString("description");
                    return new Company(r.getLong("id"),r.getString("name"),r.getString("city"),description,r.getString("role"),profile,
                        incomplete || description==null || description.isBlank());
                },id);
    }
    @Transactional
    public Company update(Update input) {
        var company=current();admin(company);
        if (input==null || input.name()==null || input.name().isBlank() || input.name().trim().length()>100
                || input.description()==null || input.description().isBlank() || input.description().length()>5000)
            throw new IllegalArgumentException("公司全称和简介必填，公司全称最多100字，简介最多5000字");
        var profile=catalog.validate(input.profile());
        String city=profile.get("cityName");
        String stored;
        try { stored=json.writeValueAsString(profile); }
        catch (Exception e) { throw new IllegalArgumentException("公司资料格式不正确"); }
        db.update("UPDATE recruitment_company SET name=?,city=?,description=?,profile_json=? WHERE id=?",input.name().trim(),city,input.description().trim(),stored,company.id());
        return current();
    }
    @Transactional
    public List<Member> members() {
        var company=current();
        return db.query("SELECT u.id,u.username,m.role FROM recruitment_company_member m JOIN sys_user u ON u.id=m.user_id WHERE m.company_id=? AND u.deleted=0 ORDER BY u.id",
                (r,n)->new Member(r.getLong(1),r.getString(2),r.getString(3)),company.id());
    }
    @Transactional
    public Member addMember(NewMember input) {
        var company=current();admin(company);
        String role=input==null || input.role()==null?"HR":input.role();
        if(!List.of("HR","INTERVIEWER").contains(role)) throw new IllegalArgumentException("请选择HR或面试官角色");
        if (input==null || input.username()==null || !input.username().matches("[A-Za-z0-9_-]{3,32}")
                || input.password()==null || input.password().length()<8 || input.password().length()>64)
            throw new IllegalArgumentException("账号须为3～32位字母数字或下划线/连字符，初始密码须为8～64位");
        var user=new UserEntity();user.setUsername(input.username());user.setPassword(passwords.encode(input.password()));user.setRole("USER");
        try { users.insert(user); }
        catch (DuplicateKeyException e) { throw new IllegalArgumentException("账号已存在，请使用新的账号名称"); }
        db.update("INSERT INTO recruitment_company_member (user_id,company_id,role) VALUES (?,?,?)",user.getId(),company.id(),role);
        return new Member(user.getId(),user.getUsername(),role);
    }
    private void admin(Company company) {
        if (!"ADMIN".equals(company.memberRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
