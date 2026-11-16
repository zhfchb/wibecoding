package com.example.agent.recruitment.followup;

import com.example.agent.recruitment.access.PositionAccess;
import com.example.agent.recruitment.access.TaskAuthority;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Human communication ledger; does not expose interviewer drafts or change outcomes. */
@Service @RequiredArgsConstructor
public class CandidateNoteService {
    private final JdbcTemplate db;
    private final PositionAccess access;
    private final TaskAuthority activity;
    private final Clock clock;
    public record Input(String kind,String content,Instant nextContactAt,String requestKey) {}
    public record Note(Long id,String kind,String content,Instant nextContactAt,Long actorId,String username,Instant createdAt) {}
    public record Page(List<Note> records,long total,int current,int size) {}
    public Page list(Long resumeId,int page,int size) {
        access.resume(resumeId);
        if(page<1 || page>100000 || size<1 || size>100) throw new IllegalArgumentException("分页参数无效");
        var rows=db.query("SELECT n.*,u.username FROM recruitment_candidate_note n LEFT JOIN sys_user u ON u.id=n.actor_id WHERE n.resume_id=? ORDER BY n.id DESC LIMIT ? OFFSET ?",
                (r,i)->new Note(r.getLong("id"),r.getString("kind"),r.getString("content"),r.getObject("next_contact_at")==null?null:r.getObject("next_contact_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getLong("actor_id"),r.getString("username"),r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),resumeId,size,(page-1)*size);
        return new Page(rows,db.queryForObject("SELECT COUNT(*) FROM recruitment_candidate_note WHERE resume_id=?",Long.class,resumeId),page,size);
    }
    @Transactional
    public void append(Long resumeId,Input input) {
        var resume=access.resume(resumeId);
        var position=access.operator(resume.getPositionId(),access.user(),true);
        if(input==null || !List.of("FOLLOWUP","REVIEW_SUMMARY").contains(input.kind()==null?"":input.kind())) throw new IllegalArgumentException("记录类型无效");
        if("REVIEW_SUMMARY".equals(input.kind()) && !access.manages(position)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(input.content()==null || input.content().isBlank() || input.content().strip().length()>3000) throw new IllegalArgumentException("记录内容须为1～3000字符");
        if(input.requestKey()==null || !input.requestKey().matches("[A-Za-z0-9_-]{8,80}")) throw new IllegalArgumentException("请求标识无效");
        var next=input.nextContactAt()==null?null:LocalDateTime.ofInstant(input.nextContactAt(),ZoneOffset.UTC);
        var prior=db.queryForList("SELECT actor_id,kind,content,next_contact_at FROM recruitment_candidate_note WHERE resume_id=? AND request_key=?",resumeId,input.requestKey());
        if(!prior.isEmpty()) {
            var row=prior.get(0);
            var savedNext=row.get("next_contact_at");
            if(savedNext instanceof java.sql.Timestamp timestamp) savedNext=timestamp.toLocalDateTime();
            if(((Number)row.get("actor_id")).longValue()!=access.user() || !Objects.equals(row.get("kind"),input.kind()) || !Objects.equals(row.get("content"),input.content().strip()) || !Objects.equals(savedNext,next))
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            return;
        }
        if(next!=null && (!"FOLLOWUP".equals(input.kind()) || !input.nextContactAt().isAfter(clock.instant()))) throw new IllegalArgumentException("下次跟进时间须晚于当前时间，且仅适用于跟进记录");
        if(db.queryForList("SELECT id FROM candidate_resume WHERE id=? AND position_id=? AND user_id=? FOR UPDATE",Long.class,resumeId,position.getId(),position.getUserId()).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        db.update("INSERT INTO recruitment_candidate_note(resume_id,position_id,actor_id,kind,content,next_contact_at,request_key,created_at) VALUES(?,?,?,?,?,?,?,?)",
                resumeId,position.getId(),access.user(),input.kind(),input.content().strip(),next,input.requestKey(),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        activity.log(position.getId(),access.user(),"REVIEW_SUMMARY".equals(input.kind())?"CANDIDATE_REVIEW_SUMMARY":"CANDIDATE_FOLLOWUP",resumeId.toString());
    }
}
