package com.example.agent.service.company;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** Human outcome ledger, independent from screening, evaluation and AI task state. */
@Service @RequiredArgsConstructor
public class RecruitmentOutcomeService {
    private final PositionAccess access;
    private final JdbcTemplate db;
    private final TaskAuthority activity;
    private final Clock clock;
    public record State(int version,String outcome,boolean archived,String reason,Long actorId,LocalDateTime updatedAt) {}
    public record Input(Integer version,String action,String outcome,String reason) {}
    public record Event(int version,String action,String outcome,boolean archived,String reason,Long actorId,String username,LocalDateTime changedAt) {}
    private State current(Long resumeId,boolean lock) {
        var rows=db.query("SELECT * FROM recruitment_candidate_outcome WHERE resume_id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new State(r.getInt("version"),r.getString("outcome"),r.getBoolean("archived"),r.getString("reason"),r.getLong("actor_id"),r.getObject("updated_at",LocalDateTime.class)),resumeId);
        return rows.isEmpty()?new State(0,"PENDING",false,null,null,null):rows.get(0);
    }
    public State get(Long resumeId) {access.resume(resumeId);return current(resumeId,false);}
    public List<Event> history(Long resumeId) {
        access.resume(resumeId);
        return db.query("SELECT e.*,u.username FROM recruitment_candidate_outcome_event e LEFT JOIN sys_user u ON u.id=e.actor_id WHERE e.resume_id=? ORDER BY e.version DESC",
            (r,n)->new Event(r.getInt("version"),r.getString("action"),r.getString("outcome"),r.getBoolean("archived"),r.getString("reason"),r.getLong("actor_id"),r.getString("username"),r.getObject("changed_at",LocalDateTime.class)),resumeId);
    }
    @Transactional
    public State change(Long resumeId,Input input) {
        var resume=access.resume(resumeId);
        access.operator(resume.getPositionId(),access.user(),true);
        // Re-read after waiting for the shared job lock (also used by source deletion).
        var live=db.queryForList("SELECT id FROM candidate_resume WHERE id=? AND position_id=? AND user_id=? FOR UPDATE",Long.class,resumeId,resume.getPositionId(),resume.getUserId());
        if(live.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        var before=current(resumeId,true);
        if(input==null || input.version()==null) throw new IllegalArgumentException("缺少结果版本，请刷新后重试");
        if(!Objects.equals(input.version(),before.version())) throw new InterviewScheduleConflict("OUTCOME_VERSION","招聘结果已被其他操作更新，请关闭并刷新后重试");
        String reason=input.reason()==null?"":input.reason().trim();
        if(reason.isEmpty() || reason.length()>2000) throw new IllegalArgumentException("请填写1～2000字的操作原因");
        String outcome=before.outcome();boolean archived=before.archived();
        switch(input.action()==null?"":input.action()) {
            case "DECIDE" -> {
                if(archived) throw new IllegalArgumentException("请先重新开启归档记录，再修改结果");
                if(!List.of("PENDING","HIRED","REJECTED","WITHDRAWN").contains(input.outcome()==null?"":input.outcome())) throw new IllegalArgumentException("请选择有效的招聘结果");
                if(outcome.equals(input.outcome())) throw new IllegalArgumentException("结果未变化，无需重复保存");
                outcome=input.outcome();
            }
            case "ARCHIVE" -> {if(archived || "PENDING".equals(outcome)) throw new IllegalArgumentException("仅已明确结果且未归档的记录可以归档");archived=true;}
            case "REOPEN" -> {if(!archived) throw new IllegalArgumentException("记录尚未归档");archived=false;}
            default -> throw new IllegalArgumentException("操作类型无效");
        }
        int version=before.version()+1;Long actor=access.user();var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        if(before.version()==0) db.update("INSERT INTO recruitment_candidate_outcome(resume_id,version,outcome,archived,reason,actor_id,updated_at) VALUES (?,?,?,?,?,?,?)",resumeId,version,outcome,archived,reason,actor,now);
        else db.update("UPDATE recruitment_candidate_outcome SET version=?,outcome=?,archived=?,reason=?,actor_id=?,updated_at=? WHERE resume_id=?",version,outcome,archived,reason,actor,now,resumeId);
        db.update("INSERT INTO recruitment_candidate_outcome_event(resume_id,version,action,outcome,archived,reason,actor_id,changed_at) VALUES (?,?,?,?,?,?,?,?)",resumeId,version,input.action(),outcome,archived,reason,actor,now);
        activity.log(resume.getPositionId(),actor,"OUTCOME_"+input.action(),resumeId.toString());
        return new State(version,outcome,archived,reason,actor,now);
    }
    public void cleanup(Long resumeId) {
        db.update("DELETE FROM recruitment_candidate_outcome_event WHERE resume_id=?",resumeId);
        db.update("DELETE FROM recruitment_candidate_outcome WHERE resume_id=?",resumeId);
    }
}
