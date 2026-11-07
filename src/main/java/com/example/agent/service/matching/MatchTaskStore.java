package com.example.agent.service.matching;

import com.example.agent.domain.recruitment.MatchTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MatchTaskStore {
    private final JdbcTemplate db;

    public record Task(MatchTaskResponse view, Long ownerId, String inputHash) {}

    private static final RowMapper<Task> ROW = (r, n) -> new Task(new MatchTaskResponse(
            r.getString("id"), r.getLong("position_id"), r.getString("request_key"), r.getString("status"), r.getString("phase"),
            r.getInt("completed_batches"), r.getInt("total_batches"), r.getInt("candidate_count"),
            r.getInt("degraded_count"), r.getString("error_code"), r.getString("error_message"),
            r.getTimestamp("created_at").toLocalDateTime(), r.getTimestamp("updated_at").toLocalDateTime(),
            r.getTimestamp("deadline_at").toLocalDateTime()), r.getLong("owner_id"), r.getString("input_hash"));

    public Task get(String id) { return one("SELECT * FROM recruitment_match_task WHERE id=?", id); }
    public Task lock(String id) { return one("SELECT * FROM recruitment_match_task WHERE id=? FOR UPDATE", id); }
    public Task byKey(Long positionId, String key) {
        var alias = one("SELECT t.* FROM recruitment_match_task t JOIN recruitment_match_request r ON r.task_id=t.id WHERE r.position_id=? AND r.request_key=?", positionId, key);
        // Also supports task records created before request aliases were introduced.
        return alias != null ? alias : one("SELECT * FROM recruitment_match_task WHERE position_id=? AND request_key=?", positionId, key);
    }
    public void rememberKey(Long positionId, String key, String taskId) {
        db.update("INSERT INTO recruitment_match_request (position_id,request_key,task_id,created_at) VALUES (?,?,?,?)",
                positionId, key, taskId, LocalDateTime.now());
    }
    public Task active(Long positionId) {
        return one("SELECT * FROM recruitment_match_task WHERE position_id=? AND status IN ('QUEUED','RUNNING') ORDER BY created_at DESC LIMIT 1", positionId);
    }
    public Task latest(Long positionId) {
        return one("SELECT * FROM recruitment_match_task WHERE position_id=? ORDER BY created_at DESC, id DESC LIMIT 1", positionId);
    }
    private Task one(String sql, Object... args) {
        var rows = db.query(sql, ROW, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(String id, Long positionId, Long ownerId, String key, String hash,
                       int count, LocalDateTime now, LocalDateTime deadline) {
        db.update("""
                INSERT INTO recruitment_match_task
                (id,position_id,owner_id,request_key,input_hash,status,phase,completed_batches,total_batches,
                 candidate_count,degraded_count,created_at,updated_at,deadline_at)
                VALUES (?,?,?,?,?,'QUEUED','QUEUED',0,?,?,0,?,?,?)
                """, id, positionId, ownerId, key, hash, (count + 19) / 20, count, now, now, deadline);
    }

    public List<Task> queued() {
        return db.query("SELECT * FROM recruitment_match_task WHERE status='QUEUED' ORDER BY created_at LIMIT 8", ROW);
    }

    public boolean claim(String id) {
        var now = LocalDateTime.now();
        return db.update("UPDATE recruitment_match_task SET status='RUNNING',phase='RULE_SCORING',updated_at=? WHERE id=? AND status='QUEUED' AND deadline_at>?", now, id, now) == 1;
    }

    public void ensureRunning(String id) {
        if (Thread.currentThread().isInterrupted()) {
            throw new MatchTaskFailure("INTERRUPTED", "执行线程已中断，原结果保留，请确认后重新匹配");
        }
        var task = get(id);
        if (task == null || !"RUNNING".equals(task.view.status()) || !task.view.deadlineAt().isAfter(LocalDateTime.now())) {
            throw new MatchTaskFailure("TASK_INACTIVE", "任务已终止或超时，请查看最新状态");
        }
    }

    public void progress(String id, String phase, int completed) {
        ensureRunning(id);
        db.update("UPDATE recruitment_match_task SET phase=?,completed_batches=?,updated_at=? WHERE id=? AND status='RUNNING'",
                phase, completed, LocalDateTime.now(), id);
    }

    public void complete(String id, int degradedCount) {
        var now = LocalDateTime.now();
        if (db.update("UPDATE recruitment_match_task SET status='SUCCEEDED',phase='FINISHED',completed_batches=total_batches,degraded_count=?,updated_at=? WHERE id=? AND status='RUNNING' AND deadline_at>?",
                degradedCount, now, id, now) != 1) {
            throw new MatchTaskFailure("TASK_INACTIVE", "任务已终止或超时，未写入匹配结果");
        }
    }

    public void fail(String id, String code, String message) {
        db.update("UPDATE recruitment_match_task SET status='FAILED',phase='FINISHED',error_code=?,error_message=?,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')",
                code, message, LocalDateTime.now(), id);
    }

    public void cancel(String id) {
        db.update("UPDATE recruitment_match_task SET status='CANCELLED',phase='FINISHED',updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')", LocalDateTime.now(), id);
    }

    public void expire() {
        var now = LocalDateTime.now();
        db.update("UPDATE recruitment_match_task SET status='FAILED',phase='FINISHED',error_code='TIMEOUT',error_message='任务超时或执行进程中断，请确认后重新匹配',updated_at=? WHERE status IN ('QUEUED','RUNNING') AND deadline_at<=?", now, now);
    }
}
