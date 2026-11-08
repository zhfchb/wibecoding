package com.example.agent.service.company;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class InterviewScheduleService {
    private final JdbcTemplate db;
    private final PositionAccess access;
    private final TaskAuthority activity;
    private final Clock clock;
    public record Schedule(Integer version,String status,Instant startAt,Instant endAt,String timeZone,LocalDateTime localStart,LocalDateTime localEnd,String mode,String location,String meetingUrl,String reason,Long updatedBy,Instant updatedAt) {}
    public record Input(Integer version,LocalDateTime localStart,LocalDateTime localEnd,String timeZone,String mode,String location,String meetingUrl,String reason) {}
    public record Transition(Integer version,String action,String reason) {}
    public record Event(Long id,Integer version,String status,Instant startAt,Instant endAt,String timeZone,String mode,String location,String meetingUrl,String reason,Long actorId,String username,Instant changedAt) {}
    private record Assignment(Long id,Long positionId,Long resumeId,Long interviewerId,String status) {}
    private static final RowMapper<Schedule> ROW=(r,n)->read(r,"");
    private static Instant instant(ResultSet r,String column) throws SQLException { return r.getObject(column,LocalDateTime.class).toInstant(ZoneOffset.UTC); }
    private static Schedule read(ResultSet r,String prefix) throws SQLException {
        Instant start=instant(r,"start_utc"),end=instant(r,"end_utc");String zone=r.getString("time_zone");
        return new Schedule(r.getInt(prefix+"version"),r.getString(prefix+"status"),start,end,zone,
                LocalDateTime.ofInstant(start,ZoneId.of(zone)),LocalDateTime.ofInstant(end,ZoneId.of(zone)),r.getString("mode"),r.getString("location"),r.getString("meeting_url"),r.getString("reason"),r.getLong("updated_by"),instant(r,"schedule_updated_at"));
    }
    public static Schedule fromAssignmentRow(ResultSet r) throws SQLException {
        return r.getObject("schedule_id")==null?null:read(r,"schedule_");
    }
    public String flow(String assignmentStatus,Schedule schedule) {
        if("REVOKED".equals(assignmentStatus)) return "REVOKED";
        if("SUBMITTED".equals(assignmentStatus)) return "EVALUATED";
        if(schedule==null) return "UNSCHEDULED";
        return switch(schedule.status()) {
            case "CANCELLED" -> "CANCELLED";
            case "NO_SHOW" -> "NO_SHOW";
            case "COMPLETED" -> "AWAITING_EVALUATION";
            default -> clock.instant().isBefore(schedule.startAt())?"UPCOMING":clock.instant().isBefore(schedule.endAt())?"IN_PROGRESS":"AWAITING_CONFIRMATION";
        };
    }
    private Schedule get(Long id,boolean lock) {
        var rows=db.query("SELECT *,updated_at AS schedule_updated_at FROM recruitment_interview_schedule WHERE assignment_id=?"+(lock?" FOR UPDATE":""),ROW,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Assignment assignment(Long id,boolean lock) {
        var rows=db.query("SELECT id,position_id,resume_id,interviewer_id,status FROM recruitment_interview_assignment WHERE id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Assignment(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getString(5)),id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.get(0);
    }
    private Assignment managed(Long id,boolean planning) {
        var initial=assignment(id,false);var position=access.operator(initial.positionId(),access.user(),true);
        if(planning && "CLOSED".equals(position.getStatus())) throw conflict("INACTIVE","岗位已关闭，不能新增或修改排期，仍可取消或确认已有安排");
        var a=assignment(id,true);
        if("REVOKED".equals(a.status())) throw conflict("INACTIVE","面试分配已撤销，不能修改排期");
        if("SUBMITTED".equals(a.status())) throw conflict("FINALIZED","评价已提交，不能修改排期；如需新一轮面试请重新分配");
        long valid=db.queryForObject("""
            SELECT COUNT(*) FROM job_position p JOIN candidate_resume r ON r.position_id=p.id AND r.user_id=p.user_id
            JOIN recruitment_company_member i ON i.company_id=p.company_id AND i.user_id=? AND i.role='INTERVIEWER'
            JOIN sys_user u ON u.id=i.user_id AND u.deleted=0
            JOIN recruitment_company_member c ON c.user_id=p.user_id AND c.company_id=p.company_id AND c.role IN ('ADMIN','HR')
            WHERE p.id=? AND r.id=?
            """,Long.class,a.interviewerId(),a.positionId(),a.resumeId());
        if(valid==0) throw conflict("INACTIVE","岗位已关闭、候选人不可用或面试官成员关系已变化");
        return a;
    }
    private LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private Instant convert(LocalDateTime local,ZoneId zone) {
        if(local==null || local.getSecond()!=0 || local.getNano()!=0) throw new IllegalArgumentException("请填写精确到分钟的开始和结束时间");
        var offsets=zone.getRules().getValidOffsets(local);
        if(offsets.isEmpty()) throw new IllegalArgumentException("所选时间在该时区因夏令时跳变而不存在");
        if(offsets.size()!=1) throw new IllegalArgumentException("所选时间在该时区因夏令时重复而有歧义，请选择其他时间");
        return local.toInstant(offsets.get(0));
    }
    private void version(Schedule current,Integer expected) {
        if(expected==null || expected!=(current==null?0:current.version())) throw conflict("VERSION","排期已变化，请刷新后重新核对");
    }
    private String reason(String value,boolean required) {
        if(value!=null && value.length()>500) throw new IllegalArgumentException("变更原因最多500字");
        if(required && (value==null || value.isBlank())) throw new IllegalArgumentException("请填写改期、取消或未到场的原因");
        return value==null?null:value.strip();
    }
    // READ_COMMITTED makes the overlap query fresh after the interviewer's user-row lock.
    // The job lock serializes bookings for the same resume; the user lock spans different jobs.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Schedule put(Long id,Input input) {
        var a=managed(id,true);
        db.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE",Long.class,a.interviewerId());
        var current=get(id,true);
        if(input==null) throw new IllegalArgumentException("请填写排期信息");version(current,input.version());
        if(current!=null && List.of("COMPLETED","NO_SHOW").contains(current.status())) throw conflict("FINALIZED","已结束的面试不能改期，请另建面试分配");
        if(input.timeZone()==null || !ZoneId.getAvailableZoneIds().contains(input.timeZone())) throw new IllegalArgumentException("请选择有效的IANA时区");
        var zone=ZoneId.of(input.timeZone());var start=convert(input.localStart(),zone);var end=convert(input.localEnd(),zone);
        long minutes=Duration.between(start,end).toMinutes();
        if(!start.isAfter(clock.instant()) || minutes<5 || minutes>480) throw new IllegalArgumentException("开始时间须晚于当前时间，面试时长为5～480分钟");
        if(!List.of("VIDEO","ONSITE","PHONE").contains(input.mode()==null?"":input.mode()) || input.location()==null || input.location().isBlank() || input.location().length()>500)
            throw new IllegalArgumentException("请选择面试方式并填写地点、会议号或电话说明（最多500字）");
        if(input.meetingUrl()!=null && !input.meetingUrl().isBlank()) {
            try { var uri=URI.create(input.meetingUrl());if(!List.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || input.meetingUrl().length()>1000) throw new IllegalArgumentException(); }
            catch(IllegalArgumentException e) { throw new IllegalArgumentException("会议链接须为有效的http或https网址"); }
        }
        String reason=reason(input.reason(),current!=null);
        long overlap=db.queryForObject("""
            SELECT COUNT(*) FROM recruitment_interview_schedule s JOIN recruitment_interview_assignment a ON a.id=s.assignment_id
            WHERE s.assignment_id<>? AND s.status='SCHEDULED' AND a.status<>'REVOKED'
            AND (s.interviewer_id=? OR s.resume_id=?) AND s.start_utc<? AND s.end_utc>?
            """,Long.class,id,a.interviewerId(),a.resumeId(),utc(end),utc(start));
        if(overlap>0) throw conflict("OVERLAP","所选时间与面试官或该候选人的其他面试冲突");
        int next=current==null?1:current.version()+1;String url=input.meetingUrl()==null?null:input.meetingUrl().strip();
        if(current==null) db.update("INSERT INTO recruitment_interview_schedule(assignment_id,interviewer_id,resume_id,version,status,start_utc,end_utc,time_zone,mode,location,meeting_url,reason,updated_by,updated_at) VALUES (?,?,?,?,'SCHEDULED',?,?,?,?,?,?,?,?,?)",
                id,a.interviewerId(),a.resumeId(),next,utc(start),utc(end),input.timeZone(),input.mode(),input.location().strip(),url,reason,access.user(),utc(clock.instant()));
        else db.update("UPDATE recruitment_interview_schedule SET version=?,status='SCHEDULED',start_utc=?,end_utc=?,time_zone=?,mode=?,location=?,meeting_url=?,reason=?,updated_by=?,updated_at=? WHERE assignment_id=?",
                next,utc(start),utc(end),input.timeZone(),input.mode(),input.location().strip(),url,reason,access.user(),utc(clock.instant()),id);
        var saved=get(id,false);record(a,saved,current==null?"INTERVIEW_SCHEDULED":"INTERVIEW_RESCHEDULED");return saved;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Schedule transition(Long id,Transition input) {
        var a=managed(id,false);var current=get(id,true);
        if(current==null || input==null) throw conflict("INACTIVE","该面试尚未排期");version(current,input.version());
        if(!"SCHEDULED".equals(current.status())) throw conflict("FINALIZED","该排期已取消或结束，不能重复变更状态");
        String next=switch(input.action()==null?"":input.action()) {case "CANCEL"->"CANCELLED";case "COMPLETE"->"COMPLETED";case "NO_SHOW"->"NO_SHOW";default->throw new IllegalArgumentException("无效的排期操作");};
        if(!"CANCELLED".equals(next) && clock.instant().isBefore(current.endAt())) throw conflict("NOT_ENDED","请在计划结束时间后确认完成或未到场");
        String reason=reason(input.reason(),!"COMPLETED".equals(next));
        updateState(a,current,next,reason,"INTERVIEW_"+next);return get(id,false);
    }
    public void checkEvaluation(Long id,boolean submit) {
        var schedule=get(id,true);if(schedule==null) return;
        if(List.of("CANCELLED","NO_SHOW").contains(schedule.status())) throw conflict("BLOCKED","面试已取消或标记未到场，不能保存评价，请联系招聘负责人");
        if(submit && clock.instant().isBefore(schedule.endAt())) throw conflict("NOT_ENDED","面试尚未到计划结束时间，可先保存草稿");
    }
    public void evaluated(Long id) {
        var current=get(id,true);if(current!=null && "SCHEDULED".equals(current.status())) updateState(assignment(id,false),current,"COMPLETED","面试官已提交评价","INTERVIEW_COMPLETED");
    }
    public void revoked(Long id) {
        var current=get(id,true);if(current!=null && "SCHEDULED".equals(current.status())) updateState(assignment(id,false),current,"CANCELLED","面试分配已撤销","INTERVIEW_CANCELLED");
    }
    private void updateState(Assignment a,Schedule old,String status,String reason,String action) {
        db.update("UPDATE recruitment_interview_schedule SET status=?,version=version+1,reason=?,updated_by=?,updated_at=? WHERE assignment_id=?",status,reason,access.user(),utc(clock.instant()),a.id());
        record(a,get(a.id(),false),action);
    }
    private void record(Assignment a,Schedule value,String action) {
        db.update("INSERT INTO recruitment_interview_schedule_event(assignment_id,version,status,start_utc,end_utc,time_zone,mode,location,meeting_url,reason,actor_id,changed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                a.id(),value.version(),value.status(),utc(value.startAt()),utc(value.endAt()),value.timeZone(),value.mode(),value.location(),value.meetingUrl(),value.reason(),access.user(),utc(clock.instant()));
        activity.log(a.positionId(),access.user(),action,a.id().toString());
    }
    public List<Event> history(Long id) {
        var a=assignment(id,false);access.operator(a.positionId(),access.user(),false);
        return db.query("SELECT e.*,u.username FROM recruitment_interview_schedule_event e LEFT JOIN sys_user u ON u.id=e.actor_id WHERE e.assignment_id=? ORDER BY e.version DESC",
                (r,n)->new Event(r.getLong("id"),r.getInt("version"),r.getString("status"),instant(r,"start_utc"),instant(r,"end_utc"),r.getString("time_zone"),r.getString("mode"),r.getString("location"),r.getString("meeting_url"),r.getString("reason"),r.getLong("actor_id"),r.getString("username"),instant(r,"changed_at")),id);
    }
    public void cleanup(Long resumeId) {
        db.update("DELETE FROM recruitment_interview_schedule_event WHERE assignment_id IN (SELECT id FROM recruitment_interview_assignment WHERE resume_id=?)",resumeId);
        db.update("DELETE FROM recruitment_interview_schedule WHERE resume_id=?",resumeId);
    }
    private InterviewScheduleConflict conflict(String code,String message) { return new InterviewScheduleConflict("SCHEDULE_"+code,message); }
}
