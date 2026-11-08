package com.example.agent.service.company;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final CompanyService companies;
    private final JdbcTemplate db;
    public record Department(Long id,String name,String description,String status,LocalDateTime updatedAt) {}
    public record Input(String name,String description) {}
    private record Values(String name,String key,String description) {}
    private static final RowMapper<Department> ROW=(r,n)->new Department(r.getLong("id"),r.getString("name"),
            r.getString("description"),r.getString("status"),r.getTimestamp("updated_at").toLocalDateTime());

    @Transactional
    public List<Department> list(boolean activeOnly) {
        var company=companies.current();
        return db.query("SELECT * FROM recruitment_department WHERE company_id=?"+
                (activeOnly ? " AND status='ACTIVE'" : "")+" ORDER BY name_key,id",ROW,company.id());
    }

    @Transactional
    public Department create(Input input) {
        var company=admin();
        var values=validate(input);
        var now=LocalDateTime.now();
        try {
            db.update("INSERT INTO recruitment_department (company_id,name,name_key,description,status,created_at,updated_at) VALUES (?,?,?,?,'ACTIVE',?,?)",
                    company.id(),values.name(),values.key(),values.description(),now,now);
        } catch (DuplicateKeyException e) { throw duplicate(); }
        return db.queryForObject("SELECT * FROM recruitment_department WHERE company_id=? AND name_key=?",ROW,company.id(),values.key());
    }

    @Transactional
    public Department update(Long id,Input input) {
        var company=admin();
        owned(id,company.id());
        var values=validate(input);
        try {
            db.update("UPDATE recruitment_department SET name=?,name_key=?,description=?,updated_at=? WHERE id=? AND company_id=?",
                    values.name(),values.key(),values.description(),LocalDateTime.now(),id,company.id());
        } catch (DuplicateKeyException e) { throw duplicate(); }
        return owned(id,company.id());
    }

    @Transactional
    public Department status(Long id,Boolean enabled) {
        var company=admin();
        owned(id,company.id());
        if (enabled==null) throw new IllegalArgumentException("请选择启用或停用状态");
        db.update("UPDATE recruitment_department SET status=?,updated_at=? WHERE id=? AND company_id=?",
                enabled ? "ACTIVE" : "DISABLED",LocalDateTime.now(),id,company.id());
        return owned(id,company.id());
    }
    private CompanyService.Company admin() {
        var company=companies.current();
        if (!"ADMIN".equals(company.memberRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return company;
    }
    private Department owned(Long id,Long company) {
        var rows=db.query("SELECT * FROM recruitment_department WHERE id=? AND company_id=?",ROW,id,company);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
    private Values validate(Input input) {
        if (input==null || input.name()==null) throw new IllegalArgumentException("请输入部门名称");
        String name=input.name().strip();
        String key=Normalizer.normalize(name,Normalizer.Form.NFKC).replaceAll("\\s+"," ").strip().toLowerCase(Locale.ROOT);
        if (name.isBlank() || key.isBlank() || name.length()>100 || key.length()>100)
            throw new IllegalArgumentException("部门名称须为1～100字");
        if (input.description()!=null && input.description().length()>500) throw new IllegalArgumentException("部门说明最多500字");
        return new Values(name,key,input.description()==null ? null : input.description().strip());
    }
    private IllegalArgumentException duplicate() {
        return new IllegalArgumentException("本公司已存在同名部门（含已停用部门），请修改名称或启用原部门");
    }
}
