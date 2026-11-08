package com.example.agent.service.company;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Actor authorization is independent of the owner used by AI retrieval and stored data. */
@Service
@RequiredArgsConstructor
public class TaskAuthority {
    private final PositionAccess access;
    private final JdbcTemplate db;
    public static class Revoked extends RuntimeException {
        public Revoked() { super("任务发起人的协作权限已变化，未继续执行或写入结果，请重新授权后重新提交"); }
    }
    private record Actor(Long id,String revision) {}
    private Actor actor(String kind,String taskId,Long owner) {
        var rows=db.query("SELECT actor_id,grant_revision FROM recruitment_task_actor WHERE kind=? AND task_id=?",
                (r,n)->new Actor(r.getLong("actor_id"),r.getString("grant_revision")),kind,taskId);
        return rows.isEmpty()?new Actor(owner,null):rows.get(0);
    }
    private String revision(Long positionId,Long actor) {
        boolean lock=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        var values=db.queryForList("SELECT revision FROM recruitment_position_viewer WHERE position_id=? AND user_id=?"+(lock?" FOR UPDATE":""),String.class,positionId,actor);
        return values.isEmpty()?null:values.get(0);
    }
    public void register(String kind,String taskId,Long positionId,Long owner) {
        Long actor=access.user();
        db.update("INSERT INTO recruitment_task_actor(kind,task_id,position_id,owner_id,actor_id,grant_revision,created_at) VALUES (?,?,?,?,?,?,?)",
                kind,taskId,positionId,owner,actor,Objects.equals(owner,actor)?null:revision(positionId,actor),LocalDateTime.now());
        log(positionId,actor,kind+"_SUBMITTED",taskId);
    }
    public void before(String kind,String taskId,Long positionId,Long owner) {
        var actor=actor(kind,taskId,owner);
        try {
            boolean lock=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            var position=access.operator(positionId,actor.id(),lock);
            if(!Objects.equals(position.getUserId(),owner) || (!Objects.equals(owner,actor.id()) &&
                    (actor.revision()==null || !Objects.equals(actor.revision(),revision(positionId,actor.id()))))) throw new Revoked();
        } catch(ResponseStatusException e) { throw new Revoked(); }
    }
    public void cancelAllowed(String kind,String taskId,Long positionId,Long owner) {
        access.operator(positionId,access.user(),true);
        if(!Objects.equals(access.user(),owner) && !Objects.equals(access.user(),actor(kind,taskId,owner).id()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    public Long actorId(String kind,String taskId,Long owner) { return actor(kind,taskId,owner).id(); }
    public boolean canCancel(String kind,String taskId,Long owner) {
        return Objects.equals(access.user(),owner) || Objects.equals(access.user(),actorId(kind,taskId,owner));
    }
    public void log(Long positionId,Long actor,String action,String resourceId) {
        db.update("INSERT INTO recruitment_position_activity(position_id,actor_id,action,resource_id,created_at) VALUES (?,?,?,?,?)",
                positionId,actor,action,resourceId,LocalDateTime.now());
    }
    public record Activity(Long id,Long actorId,String username,String action,String resourceId,LocalDateTime createdAt) {}
    public List<Activity> activity(Long positionId) {
        access.operator(positionId,access.user(),false);
        return db.query("""
          SELECT a.*,u.username FROM recruitment_position_activity a LEFT JOIN sys_user u ON u.id=a.actor_id
          WHERE a.position_id=? ORDER BY a.id DESC LIMIT 100
          """,(r,n)->new Activity(r.getLong("id"),r.getLong("actor_id"),r.getString("username"),r.getString("action"),r.getString("resource_id"),r.getTimestamp("created_at").toLocalDateTime()),positionId);
    }
}
