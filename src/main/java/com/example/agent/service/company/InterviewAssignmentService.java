package com.example.agent.service.company;

import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.mapper.CandidateResumeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class InterviewAssignmentService {
    private final JdbcTemplate db;
    private final PositionAccess access;
    private final CompanyService companies;
    private final TaskAuthority activity;
    private final JobPositionMapper positions;
    private final CandidateResumeMapper resumes;
    private final ObjectMapper json;
    public record Evaluation(Integer score,String recommendation,String strengths,String concerns,String summary,LocalDateTime submittedAt) {}
    public record Assignment(Long id,Long positionId,Long resumeId,Long interviewerId,String username,String title,String candidateName,String status,Integer version,Evaluation evaluation) {}
    public record EvaluationInput(Integer version,Integer score,String recommendation,String strengths,String concerns,String summary,boolean submit) {}
    public record PositionBrief(String title,String department,String city,List<String> requiredSkills,Integer minYears,String education,String description) {}
    public record CandidateBrief(String name,List<String> skills,Integer yearsExp,String education,String lastCompany) {}
    public record Question(String category,String question,String assessPoint,String referenceAnswer,String difficulty) {}
    public record Detail(Assignment assignment,PositionBrief position,CandidateBrief candidate,List<Question> questions) {}
    private static final String BASE="""
        SELECT a.*,u.username,p.title,r.candidate_name,e.assignment_id AS evaluation_id,e.score,e.recommendation,e.strengths,e.concerns,e.summary,e.submitted_at
        FROM recruitment_interview_assignment a JOIN job_position p ON p.id=a.position_id
        JOIN candidate_resume r ON r.id=a.resume_id AND r.position_id=p.id AND r.user_id=p.user_id
        JOIN sys_user u ON u.id=a.interviewer_id
        LEFT JOIN recruitment_interview_evaluation e ON e.assignment_id=a.id
        """;
    private static final String LIVE="""
        AND u.deleted=0 AND EXISTS(SELECT 1 FROM recruitment_company_member i
          JOIN recruitment_company_member c ON c.company_id=i.company_id AND c.user_id=p.user_id AND c.role IN ('ADMIN','HR')
          WHERE i.user_id=a.interviewer_id AND i.role='INTERVIEWER' AND i.company_id=p.company_id)
        """;
    private RowMapper<Assignment> row(boolean drafts) {
        return (r,n)-> {
            var submitted=r.getTimestamp("submitted_at");
            var evaluation=r.getObject("evaluation_id")==null || (!drafts && submitted==null)?null:
                    new Evaluation(r.getObject("score",Integer.class),r.getString("recommendation"),r.getString("strengths"),r.getString("concerns"),r.getString("summary"),submitted==null?null:submitted.toLocalDateTime());
            return new Assignment(r.getLong("id"),r.getLong("position_id"),r.getLong("resume_id"),r.getLong("interviewer_id"),r.getString("username"),r.getString("title"),r.getString("candidate_name"),r.getString("status"),r.getInt("version"),evaluation);
        };
    }
    public List<Assignment> managed(Long positionId) {
        access.operator(positionId,access.user(),false);
        return db.query(BASE+" WHERE a.position_id=? ORDER BY a.id DESC",row(false),positionId);
    }
    public List<Assignment> mine() {
        return db.query(BASE+" WHERE a.interviewer_id=? AND a.status<>'REVOKED' "+LIVE+" ORDER BY a.id DESC",row(true),access.user());
    }
    private Assignment personal(Long id) {
        return personal(id,false);
    }
    private Assignment personal(Long id,boolean lock) {
        var rows=db.query(BASE+" WHERE a.id=? AND a.interviewer_id=? AND a.status<>'REVOKED' "+LIVE+(lock?" FOR UPDATE":""),row(true),id,access.user());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.get(0);
    }
    public Detail detail(Long id) {
        var assignment=personal(id);var p=positions.selectById(assignment.positionId());var r=resumes.selectById(assignment.resumeId());
        if(p==null || r==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var questions=db.query("SELECT category,question,assess_point,reference_answer,difficulty FROM interview_question WHERE resume_id=? AND position_id=? ORDER BY id",
                (q,n)->new Question(q.getString(1),q.getString(2),q.getString(3),q.getString(4),q.getString(5)),r.getId(),p.getId());
        return new Detail(assignment,new PositionBrief(p.getTitle(),p.getDepartment(),p.getCity(),skills(p.getRequiredSkills()),p.getMinYears(),p.getEducation(),p.getDescription()),
                new CandidateBrief(r.getCandidateName(),skills(r.getSkills()),r.getYearsExp(),r.getEducation(),r.getLastCompany()),questions);
    }
    private List<String> skills(String raw) {
        if(raw==null) return List.of();
        try { return json.readValue(raw,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}); }
        catch(Exception e) { return List.of(); }
    }
    @Transactional
    public Assignment assign(Long resumeId,Long interviewer) {
        var resume=access.resume(resumeId);
        var position=access.operator(resume.getPositionId(),access.user(),true);
        if("CLOSED".equals(position.getStatus())) throw new IllegalArgumentException("岗位已关闭，不能新增面试分配");
        if(!"PARSED".equals(resume.getParseStatus())) throw new IllegalArgumentException("候选人尚未解析成功，请先完善资料");
        var company=companies.current();
        if(position.getCompanyId()!=null && !Objects.equals(position.getCompanyId(),company.id())) throw new IllegalArgumentException("请先核对岗位公司归属");
        if(interviewer==null || db.queryForObject("SELECT COUNT(*) FROM recruitment_company_member m JOIN sys_user u ON u.id=m.user_id AND u.deleted=0 WHERE m.company_id=? AND m.user_id=? AND m.role='INTERVIEWER'",Long.class,company.id(),interviewer)==0)
            throw new IllegalArgumentException("请选择本公司的面试官成员");
        if(position.getCompanyId()==null) {
            long active=db.queryForObject("""
              SELECT (SELECT COUNT(*) FROM recruitment_match_task WHERE position_id=? AND status IN ('QUEUED','RUNNING'))
              +(SELECT COUNT(*) FROM recruitment_resume_parse_task WHERE position_id=? AND status IN ('QUEUED','RUNNING'))
              +(SELECT COUNT(*) FROM recruitment_material_task WHERE resume_id IN (SELECT id FROM candidate_resume WHERE position_id=?) AND status IN ('QUEUED','RUNNING'))
              """,Long.class,position.getId(),position.getId(),position.getId());
            if(active>0) throw new IllegalArgumentException("旧岗位有进行中任务，请结束后再确认公司归属并分配面试官");
            db.update("UPDATE job_position SET company_id=? WHERE id=?",company.id(),position.getId());
        }
        var existing=db.query(BASE+" WHERE a.resume_id=? AND a.interviewer_id=? AND a.status<>'REVOKED' FOR UPDATE",row(false),resumeId,interviewer);
        if(!existing.isEmpty()) return existing.get(0);
        db.update("INSERT INTO recruitment_interview_assignment(position_id,resume_id,interviewer_id,assigned_by,status,version,created_at,updated_at) VALUES (?,?,?,?,'ASSIGNED',0,?,?)",
                position.getId(),resumeId,interviewer,access.user(),LocalDateTime.now(),LocalDateTime.now());
        var assigned=db.query(BASE+" WHERE a.resume_id=? AND a.interviewer_id=? AND a.status='ASSIGNED'",row(false),resumeId,interviewer).get(0);
        activity.log(position.getId(),access.user(),"INTERVIEW_ASSIGNED",assigned.id().toString());return assigned;
    }
    private Long positionId(Long id) {
        var rows=db.queryForList("SELECT position_id FROM recruitment_interview_assignment WHERE id=?",Long.class,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.get(0);
    }
    @Transactional
    public void revoke(Long id) {
        Long positionId=positionId(id);access.operator(positionId,access.user(),true);
        if(db.update("UPDATE recruitment_interview_assignment SET status='REVOKED',version=version+1,updated_at=? WHERE id=? AND status<>'REVOKED'",LocalDateTime.now(),id)>0)
            activity.log(positionId,access.user(),"INTERVIEW_REVOKED",id.toString());
    }
    @Transactional
    public Detail save(Long id,EvaluationInput input) {
        Long positionId=positionId(id);positions.lockById(positionId);
        db.queryForList("SELECT id FROM recruitment_interview_assignment WHERE id=? FOR UPDATE",Long.class,id);
        var assignment=personal(id,true);
        if("SUBMITTED".equals(assignment.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"评价已提交，不能修改");
        if(input==null || input.version()==null) throw new IllegalArgumentException("缺少评价版本，请重新加载");
        if(!Objects.equals(input.version(),assignment.version())) throw new ResponseStatusException(HttpStatus.CONFLICT,"评价版本已变化，请重新加载");
        if(input.score()!=null && (input.score()<1 || input.score()>5)) throw new IllegalArgumentException("评分范围为1～5");
        if(input.recommendation()!=null && !List.of("PASS","HOLD","NO").contains(input.recommendation())) throw new IllegalArgumentException("评价建议无效");
        if(input.submit() && (input.score()==null || input.recommendation()==null || input.summary()==null || input.summary().isBlank())) throw new IllegalArgumentException("提交时请填写评分、建议与评价总结");
        for(String value:Arrays.asList(input.strengths(),input.concerns(),input.summary())) if(value!=null && value.length()>3000) throw new IllegalArgumentException("每项评价最多3000字");
        LocalDateTime submitted=input.submit()?LocalDateTime.now():null;
        int updated=db.update("UPDATE recruitment_interview_evaluation SET score=?,recommendation=?,strengths=?,concerns=?,summary=?,submitted_at=? WHERE assignment_id=?",
                input.score(),input.recommendation(),input.strengths(),input.concerns(),input.summary(),submitted,id);
        if(updated==0) db.update("INSERT INTO recruitment_interview_evaluation(assignment_id,score,recommendation,strengths,concerns,summary,submitted_at) VALUES (?,?,?,?,?,?,?)",
                id,input.score(),input.recommendation(),input.strengths(),input.concerns(),input.summary(),submitted);
        db.update("UPDATE recruitment_interview_assignment SET status=?,version=version+1,updated_at=? WHERE id=?",input.submit()?"SUBMITTED":"DRAFT",LocalDateTime.now(),id);
        if(input.submit()) activity.log(positionId,access.user(),"INTERVIEW_EVALUATED",id.toString());
        return detail(id);
    }
    public void cleanupForResume(Long resumeId) {
        db.update("DELETE FROM recruitment_interview_evaluation WHERE assignment_id IN (SELECT id FROM recruitment_interview_assignment WHERE resume_id=?)",resumeId);
        db.update("DELETE FROM recruitment_interview_assignment WHERE resume_id=?",resumeId);
    }
}
