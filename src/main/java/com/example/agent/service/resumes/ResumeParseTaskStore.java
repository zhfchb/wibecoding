package com.example.agent.service.resumes;

import com.example.agent.domain.recruitment.ResumeParseTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ResumeParseTaskStore {
    private final JdbcTemplate db;
    public record Task(String id, Long positionId, Long ownerId, String key, String inputHash,
                       String status, String errorCode, String errorMessage, LocalDateTime deadline) {}
    public record Item(Long resumeId, String inputHash, String status) {}
    private static final RowMapper<Task> ROW = (r, n) -> new Task(r.getString("id"), r.getLong("position_id"),
            r.getLong("owner_id"), r.getString("request_key"), r.getString("input_hash"), r.getString("status"),
            r.getString("error_code"), r.getString("error_message"), r.getTimestamp("deadline_at").toLocalDateTime());

    public Task get(String id) { return one("SELECT * FROM recruitment_resume_parse_task WHERE id=?", id); }
    public Task lock(String id) { return one("SELECT * FROM recruitment_resume_parse_task WHERE id=? FOR UPDATE", id); }
    public Task byKey(Long positionId, String key) {
        return one("SELECT * FROM recruitment_resume_parse_task WHERE position_id=? AND request_key=?", positionId, key);
    }
    public Task active(Long positionId) {
        return one("SELECT * FROM recruitment_resume_parse_task WHERE position_id=? AND status IN ('QUEUED','RUNNING') LIMIT 1", positionId);
    }
    public Task latest(Long positionId) {
        return one("SELECT * FROM recruitment_resume_parse_task WHERE position_id=? ORDER BY created_at DESC,id DESC LIMIT 1", positionId);
    }
    private Task one(String sql, Object... args) {
        var rows = db.query(sql, ROW, args); return rows.isEmpty() ? null : rows.get(0);
    }
    public void create(String id, Long positionId, Long ownerId, String key, String hash, LocalDateTime deadline) {
        var now = LocalDateTime.now();
        db.update("INSERT INTO recruitment_resume_parse_task (id,position_id,owner_id,request_key,input_hash,status,created_at,updated_at,deadline_at) VALUES (?,?,?,?,?,'QUEUED',?,?,?)",
                id, positionId, ownerId, key, hash, now, now, deadline);
    }
    public void addItem(String id, Long resumeId, int order, String hash) {
        db.update("INSERT INTO recruitment_resume_parse_item (task_id,resume_id,ordinal_no,input_hash,status) VALUES (?,?,?,?,'QUEUED')", id,resumeId,order,hash);
    }
    public List<Item> items(String id) {
        return db.query("SELECT * FROM recruitment_resume_parse_item WHERE task_id=? ORDER BY ordinal_no",
                (r, n) -> new Item(r.getLong("resume_id"),r.getString("input_hash"),r.getString("status")), id);
    }
    public ResumeParseTaskResponse view(Task task) {
        var items = db.query("""
                SELECT i.*,r.candidate_name FROM recruitment_resume_parse_item i
                LEFT JOIN candidate_resume r ON r.id=i.resume_id WHERE i.task_id=? ORDER BY i.ordinal_no
                """, (r,n) -> new ResumeParseTaskResponse.Item(r.getLong("resume_id"),r.getString("candidate_name"),
                r.getString("status"),r.getString("error_code"),r.getString("error_message")), task.id);
        return new ResumeParseTaskResponse(task.id,task.positionId,task.key,task.status,items.size(),
                (int)items.stream().filter(i -> "SUCCEEDED".equals(i.status())).count(),
                (int)items.stream().filter(i -> "FAILED".equals(i.status())).count(),
                (int)items.stream().filter(i -> "CANCELLED".equals(i.status())).count(),
                task.errorCode,task.errorMessage,task.deadline,items);
    }
    public List<Task> queued() {
        return db.query("SELECT * FROM recruitment_resume_parse_task WHERE status='QUEUED' ORDER BY created_at LIMIT 8", ROW);
    }
    public boolean claim(String id) {
        var now = LocalDateTime.now();
        return db.update("UPDATE recruitment_resume_parse_task SET status='RUNNING',updated_at=? WHERE id=? AND status='QUEUED' AND deadline_at>?",now,id,now)==1;
    }
    public void itemStatus(String id, Long resumeId, String status, String code, String message) {
        db.update("UPDATE recruitment_resume_parse_item SET status=?,error_code=?,error_message=? WHERE task_id=? AND resume_id=? AND status IN ('QUEUED','RUNNING')",status,code,message,id,resumeId);
    }
    public void finish(String id) {
        var items = items(id);
        boolean all = items.stream().allMatch(i -> "SUCCEEDED".equals(i.status));
        boolean some = items.stream().anyMatch(i -> "SUCCEEDED".equals(i.status));
        db.update("UPDATE recruitment_resume_parse_task SET status=?,updated_at=? WHERE id=? AND status='RUNNING'",
                all ? "SUCCEEDED" : some ? "PARTIAL" : "FAILED",LocalDateTime.now(),id);
    }
    public void terminate(String id, String status, String code, String message) {
        db.update("UPDATE recruitment_resume_parse_task SET status=?,error_code=?,error_message=?,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')",status,code,message,LocalDateTime.now(),id);
        db.update("UPDATE recruitment_resume_parse_item SET status=?,error_code=?,error_message=? WHERE task_id=? AND status IN ('QUEUED','RUNNING')",status,code,message,id);
    }
    public List<Task> expired() {
        return db.query("SELECT * FROM recruitment_resume_parse_task WHERE status IN ('QUEUED','RUNNING') AND deadline_at<=?",ROW,LocalDateTime.now());
    }
}
