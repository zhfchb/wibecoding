package com.example.agent.service.company;

import com.example.agent.entity.JobPositionEntity;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Objects;

/** Atomic department validation and position persistence, also used by JD imports. */
@Service
@RequiredArgsConstructor
public class PositionDepartmentService {
    private final CompanyService companies;
    private final JdbcTemplate db;
    private final JobPositionMapper positions;
    public record View(String name, String status) {}

    @Transactional
    public void create(JobPositionEntity entity, Long departmentId) {
        entity.setCompanyId(companies.current().id());
        if (departmentId != null) select(entity, departmentId);
        positions.insert(entity);
    }

    @Transactional
    public void update(JobPositionEntity entity, Long departmentId) {
        var previous = ownedLocked(entity.getId());
        entity.setCompanyId(previous.getCompanyId());
        entity.setDepartmentId(previous.getDepartmentId());
        if (previous.getDepartmentId() != null) entity.setDepartment(previous.getDepartment());
        if (departmentId != null && !Objects.equals(departmentId, previous.getDepartmentId())) {
            entity.setCompanyId(companies.current().id());
            select(entity, departmentId);
        }
        positions.updateById(entity);
    }

    @Transactional
    public void bind(Long positionId, Long departmentId) {
        if (departmentId == null) throw new IllegalArgumentException("请选择已启用的公司部门");
        var entity = ownedLocked(positionId);
        entity.setCompanyId(companies.current().id());
        select(entity, departmentId);
        // Update only association fields; do not overwrite concurrent edits to the job requirements.
        db.update("UPDATE job_position SET company_id=?,department_id=?,department=?,updated_at=? WHERE id=? AND user_id=?",
                entity.getCompanyId(), entity.getDepartmentId(), entity.getDepartment(), LocalDateTime.now(), positionId, SecurityUtils.getCurrentUserId());
    }

    private JobPositionEntity ownedLocked(Long id) {
        var owners = db.queryForList("SELECT user_id FROM job_position WHERE id=? FOR UPDATE", Long.class, id);
        if (owners.isEmpty() || !Objects.equals(owners.get(0), SecurityUtils.getCurrentUserId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return positions.selectById(id);
    }

    private void select(JobPositionEntity entity, Long departmentId) {
        var rows = db.query("SELECT name,status FROM recruitment_department WHERE id=? AND company_id=? FOR UPDATE",
                (r,n) -> new View(r.getString("name"), r.getString("status")), departmentId, entity.getCompanyId());
        if (rows.isEmpty()) throw new IllegalArgumentException("部门不存在或不属于当前公司");
        var department = rows.get(0);
        if (!"ACTIVE".equals(department.status())) throw new IllegalArgumentException("部门已停用，请选择已启用的部门");
        entity.setDepartmentId(departmentId);
        entity.setDepartment(department.name());
    }

    public View view(JobPositionEntity entity) {
        if (entity.getDepartmentId() == null) return new View(entity.getDepartment(), null);
        var rows = db.query("SELECT name,status FROM recruitment_department WHERE id=? AND company_id=?",
                (r,n) -> new View(r.getString("name"),r.getString("status")), entity.getDepartmentId(),entity.getCompanyId());
        return rows.isEmpty() ? new View(entity.getDepartment(), "UNAVAILABLE") : rows.get(0);
    }
}
