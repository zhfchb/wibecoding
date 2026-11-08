package com.example.agent.service.company;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PositionCollaborationService {
    private final PositionAccess access;
    private final CompanyService companies;
    private final JdbcTemplate db;
    public record Viewer(Long userId,String username,String permission) {}

    public List<Viewer> list(Long positionId) {
        access.owned(positionId);
        return db.query("""
          SELECT v.user_id,u.username,v.permission FROM recruitment_position_viewer v
          JOIN sys_user u ON u.id=v.user_id WHERE v.position_id=? ORDER BY u.username,v.user_id
          """,(r,n)->new Viewer(r.getLong("user_id"),r.getString("username"),r.getString("permission")),positionId);
    }
    @Transactional
    public void grant(Long positionId,Long userId) {
        grant(positionId,userId,"VIEWER");
    }
    @Transactional
    public void grant(Long positionId,Long userId,String permission) {
        if(!List.of("VIEWER","EDITOR").contains(permission==null?"":permission)) throw new IllegalArgumentException("请选择只读或招聘协作权限");
        // Locking the position serializes grants/revocations/deletion. No bulk legacy backfill.
        db.queryForList("SELECT id FROM job_position WHERE id=? FOR UPDATE",Long.class,positionId);
        var position=access.owned(positionId);
        var company=companies.current();
        if(userId==null || Objects.equals(userId,access.user())) throw new IllegalArgumentException("请选择本公司的其他成员");
        if(position.getCompanyId()!=null && !Objects.equals(position.getCompanyId(),company.id()))
            throw new IllegalArgumentException("岗位公司归属与当前成员关系不一致，请先核对");
        if(db.queryForObject("SELECT COUNT(*) FROM recruitment_company_member WHERE company_id=? AND user_id=?",Long.class,company.id(),userId)==0)
            throw new IllegalArgumentException("只能授权本公司的成员");
        if(position.getCompanyId()==null) {
            long active=db.queryForObject("""
              SELECT (SELECT COUNT(*) FROM recruitment_match_task WHERE position_id=? AND status IN ('QUEUED','RUNNING'))
                +(SELECT COUNT(*) FROM recruitment_resume_parse_task WHERE position_id=? AND status IN ('QUEUED','RUNNING'))
                +(SELECT COUNT(*) FROM recruitment_material_task WHERE resume_id IN (SELECT id FROM candidate_resume WHERE position_id=?) AND status IN ('QUEUED','RUNNING'))
              """,Long.class,positionId,positionId,positionId);
            if(active>0) throw new IllegalArgumentException("旧岗位有进行中任务，请在任务结束后确认公司归属并授权");
            db.update("UPDATE job_position SET company_id=? WHERE id=?",company.id(),positionId);
        }
        if(db.queryForObject("SELECT COUNT(*) FROM recruitment_position_viewer WHERE position_id=? AND user_id=?",Long.class,positionId,userId)==0)
            db.update("INSERT INTO recruitment_position_viewer(position_id,user_id,granted_by,permission,revision,created_at) VALUES (?,?,?,?,?,?)",positionId,userId,access.user(),permission,java.util.UUID.randomUUID().toString(),LocalDateTime.now());
        else db.update("UPDATE recruitment_position_viewer SET permission=?,revision=? WHERE position_id=? AND user_id=? AND permission<>?",
                permission,java.util.UUID.randomUUID().toString(),positionId,userId,permission);
    }
    @Transactional
    public void revoke(Long positionId,Long userId) {
        db.queryForList("SELECT id FROM job_position WHERE id=? FOR UPDATE",Long.class,positionId);
        access.owned(positionId);
        db.update("DELETE FROM recruitment_position_viewer WHERE position_id=? AND user_id=?",positionId,userId);
    }
}
