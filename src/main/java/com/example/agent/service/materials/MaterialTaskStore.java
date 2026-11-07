package com.example.agent.service.materials;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MaterialTaskStore {
    private final JdbcTemplate db;
    public record Task(String id, Long resumeId, Long ownerId, String kind, String key, String hash,
                       String status, String phase, String errorCode, String errorMessage, LocalDateTime deadline) {}
    private static final RowMapper<Task> ROW = (r,n) -> new Task(r.getString("id"),r.getLong("resume_id"),
            r.getLong("owner_id"),r.getString("kind"),r.getString("request_key"),r.getString("input_hash"),
            r.getString("status"),r.getString("phase"),r.getString("error_code"),r.getString("error_message"),
            r.getTimestamp("deadline_at").toLocalDateTime());
    private Task one(String sql,Object... args) {
        var result = db.query(sql,ROW,args); return result.isEmpty() ? null : result.get(0);
    }
    public Task get(String id) { return one("SELECT * FROM recruitment_material_task WHERE id=?",id); }
    public Task lock(String id) { return one("SELECT * FROM recruitment_material_task WHERE id=? FOR UPDATE",id); }
    public Task byKey(Long resumeId,String kind,String key) {
        return one("SELECT t.* FROM recruitment_material_task t JOIN recruitment_material_request r ON r.task_id=t.id WHERE r.resume_id=? AND r.kind=? AND r.request_key=?",resumeId,kind,key);
    }
    public void remember(Long resumeId,String kind,String key,String id) {
        db.update("INSERT INTO recruitment_material_request (resume_id,kind,request_key,task_id) VALUES (?,?,?,?)",resumeId,kind,key,id);
    }
    public Task active(Long resumeId,String kind) {
        return one("SELECT * FROM recruitment_material_task WHERE resume_id=? AND kind=? AND status IN ('QUEUED','RUNNING') LIMIT 1",resumeId,kind);
    }
    public Task latest(Long resumeId,String kind) {
        return one("SELECT * FROM recruitment_material_task WHERE resume_id=? AND kind=? ORDER BY created_at DESC,id DESC LIMIT 1",resumeId,kind);
    }
    public void create(String id,Long resumeId,Long owner,String kind,String key,String hash,BigDecimal score,LocalDateTime deadline) {
        var now = LocalDateTime.now();
        db.update("INSERT INTO recruitment_material_task (id,resume_id,owner_id,kind,request_key,input_hash,source_score,status,phase,created_at,updated_at,deadline_at) VALUES (?,?,?,?,?,?,?,'QUEUED','QUEUED',?,?,?)",
                id,resumeId,owner,kind,key,hash,score,now,now,deadline);
        remember(resumeId,kind,key,id);
    }
    public List<Task> queued() {
        return db.query("SELECT * FROM recruitment_material_task WHERE status='QUEUED' ORDER BY created_at LIMIT 8",ROW);
    }
    public boolean claim(String id) {
        var now = LocalDateTime.now();
        return db.update("UPDATE recruitment_material_task SET status='RUNNING',phase='PREPARING',updated_at=? WHERE id=? AND status='QUEUED' AND deadline_at>?",now,id,now)==1;
    }
    public void running(Task task) {
        if (Thread.currentThread().isInterrupted()) throw new MaterialFailure("INTERRUPTED","执行已中断，请确认后重新生成");
        if (task == null || !"RUNNING".equals(task.status) || !task.deadline.isAfter(LocalDateTime.now()))
            throw new MaterialFailure("TASK_INACTIVE","任务已终止或超时，原内容保留");
    }
    public void progress(String id,String phase) {
        running(get(id));
        db.update("UPDATE recruitment_material_task SET phase=?,updated_at=? WHERE id=? AND status='RUNNING'",phase,LocalDateTime.now(),id);
    }
    public void complete(String id,Long resultId) {
        var now = LocalDateTime.now();
        if (db.update("UPDATE recruitment_material_task SET status='SUCCEEDED',phase='FINISHED',result_id=?,updated_at=? WHERE id=? AND status='RUNNING' AND deadline_at>?",resultId,now,id,now)!=1)
            throw new MaterialFailure("TASK_INACTIVE","任务已终止或超时，原内容保留");
    }
    public BigDecimal reportScore(Long reportId) {
        var scores = db.query("SELECT source_score FROM recruitment_material_task WHERE kind='REPORT' AND status='SUCCEEDED' AND result_id=?",(r,n) -> r.getBigDecimal(1),reportId);
        // Legacy reports have no recoverable score snapshot. Never borrow today's match score.
        return scores.isEmpty() ? null : scores.get(0);
    }
    public void terminate(String id,String status,String code,String message) {
        db.update("UPDATE recruitment_material_task SET status=?,phase='FINISHED',error_code=?,error_message=?,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')",status,code,message,LocalDateTime.now(),id);
    }
    public void expire() {
        var now = LocalDateTime.now();
        db.update("UPDATE recruitment_material_task SET status='FAILED',phase='FINISHED',error_code='TIMEOUT',error_message='任务超时或进程中断，原内容保留，请确认后重试',updated_at=? WHERE status IN ('QUEUED','RUNNING') AND deadline_at<=?",now,now);
    }
}
