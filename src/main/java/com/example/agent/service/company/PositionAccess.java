package com.example.agent.service.company;

import com.example.agent.entity.CandidateResumeEntity;
import com.example.agent.entity.JobPositionEntity;
import com.example.agent.mapper.CandidateResumeMapper;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Objects;

/** Explicit grants only. Company or system administrators have no implicit access. */
@Service
@RequiredArgsConstructor
public class PositionAccess {
    private final JdbcTemplate db;
    private final JobPositionMapper positions;
    private final CandidateResumeMapper resumes;
    private static final String GRANTED = """
        EXISTS (SELECT 1 FROM recruitment_position_viewer v
          JOIN recruitment_company_member reader_member ON reader_member.user_id=v.user_id AND reader_member.company_id=p.company_id
          JOIN recruitment_company_member creator_member ON creator_member.user_id=p.user_id AND creator_member.company_id=p.company_id
          WHERE v.position_id=p.id AND v.user_id=? AND v.granted_by=p.user_id AND v.permission IN ('VIEWER','EDITOR'))
        """;

    public Long user() {
        Long id=SecurityUtils.getCurrentUserId();
        if(id==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return id;
    }
    public boolean manages(JobPositionEntity position) {
        return position!=null && SecurityUtils.getCurrentUserId()!=null && Objects.equals(position.getUserId(),SecurityUtils.getCurrentUserId());
    }
    public JobPositionEntity owned(Long id) {
        user();var position=positions.selectById(id);
        if(!manages(position)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return position;
    }
    public JobPositionEntity lockOwned(Long id) {
        user();var position=positions.lockById(id);
        if(!manages(position)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return position;
    }
    public boolean operates(JobPositionEntity position) {
        return "OWNER".equals(permission(position,user())) || "EDITOR".equals(permission(position,user()));
    }
    public String permission(JobPositionEntity position,Long actor) {
        if(position==null || actor==null) return null;
        if(Objects.equals(position.getUserId(),actor)) return "OWNER";
        var roles=db.queryForList("""
            SELECT v.permission FROM recruitment_position_viewer v
            JOIN recruitment_company_member a ON a.user_id=v.user_id AND a.company_id=?
            JOIN recruitment_company_member c ON c.user_id=? AND c.company_id=a.company_id
            WHERE v.position_id=? AND v.user_id=? AND v.granted_by=?
            """,String.class,position.getCompanyId(),position.getUserId(),position.getId(),actor,position.getUserId());
        return roles.isEmpty()?null:roles.get(0);
    }
    public JobPositionEntity operator(Long id,Long actor,boolean lock) {
        var position=lock?positions.lockById(id):positions.selectById(id);
        String permission=permission(position,actor);
        if(!"OWNER".equals(permission) && !"EDITOR".equals(permission)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return position;
    }
    public List<Long> visibleIds() {
        Long user=user();
        return db.queryForList("SELECT p.id FROM job_position p WHERE p.user_id=? OR "+GRANTED,Long.class,user,user);
    }
    public JobPositionEntity readable(Long id) {
        Long user=user();var position=positions.selectById(id);
        if(position==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!manages(position) && db.queryForObject("SELECT COUNT(*) FROM job_position p WHERE p.id=? AND "+GRANTED,Long.class,id,user)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return position;
    }
    public CandidateResumeEntity resume(Long id) {
        user();var resume=resumes.selectById(id);
        if(resume==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var position=readable(resume.getPositionId());
        if(!Objects.equals(position.getUserId(),resume.getUserId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return resume;
    }
    public void removeGrants(Long id) {
        owned(id);
        db.update("DELETE FROM recruitment_position_viewer WHERE position_id=?",id);
    }
}
