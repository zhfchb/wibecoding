package com.example.agent.service.matching;

import com.example.agent.domain.recruitment.MatchResultResponse;
import com.example.agent.domain.recruitment.MatchTaskResponse;
import com.example.agent.mapper.JobPositionMapper;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.MatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class MatchTaskService {
    private final JobPositionMapper positions;
    private final MatchInputReader inputs;
    private final MatchTaskStore store;
    private final MatchService engine;
    private final TransactionTemplate tx;
    private final int timeoutSeconds;
    private final com.example.agent.service.company.PositionAccess access;
    private final com.example.agent.service.company.TaskAuthority authority;

    public MatchTaskService(JobPositionMapper positions, MatchInputReader inputs, MatchTaskStore store,
                            MatchService engine, TransactionTemplate tx,
                            @Value("${match.tasks.timeout-seconds:600}") int timeoutSeconds,
                            com.example.agent.service.company.PositionAccess access,com.example.agent.service.company.TaskAuthority authority) {
        this.access=access;this.authority=authority;
        this.positions = positions; this.inputs = inputs; this.store = store;
        this.engine = engine; this.tx = tx; this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public MatchTaskResponse submit(Long positionId, String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}")) {
            throw new IllegalArgumentException("请提供 8～80 位字母、数字、下划线或连字符组成的请求标识");
        }
        return view(create(positionId, key, false));
    }

    private MatchTaskStore.Task create(Long positionId, String key, boolean synchronous) {
        return tx.execute(status -> {
            var position = access.operator(positionId,access.user(),true);
            Long owner=position.getUserId();
            store.expire();
            var prior = store.byKey(positionId, key);
            if (prior != null) return prior;
            var active = store.active(positionId);
            if (active != null) {
                if (synchronous) throw new ResponseStatusException(HttpStatus.CONFLICT);
                store.rememberKey(positionId, key, active.view().id());
                return active;
            }
            var input = inputs.load(positionId, owner);
            var now = LocalDateTime.now();
            String id = UUID.randomUUID().toString();
            store.insert(id, positionId, owner, key, input.hash(), input.resumes().size(), now, now.plusSeconds(timeoutSeconds));
            authority.register("MATCH",id,positionId,owner);
            store.rememberKey(positionId, key, id);
            if (synchronous && !store.claim(id)) throw new IllegalStateException("Cannot start match task");
            return store.get(id);
        });
    }

    public MatchTaskResponse latest(Long positionId) {
        requirePosition(positionId);
        store.expire();
        var task = store.latest(positionId);
        return task == null ? null : view(task);
    }

    public MatchTaskResponse get(String id) {
        var task = owned(id);
        store.expire();
        return view(store.get(task.view().id()));
    }

    public MatchTaskResponse cancel(String id) {
        return tx.execute(status -> {
            var task=owned(id);
            authority.cancelAllowed("MATCH",id,task.view().positionId(),task.ownerId());
            if(List.of("QUEUED","RUNNING").contains(store.lock(id).view().status())) {
                store.cancel(id);authority.log(task.view().positionId(),access.user(),"MATCH_CANCELLED",id);
            }
            return get(id);
        });
    }

    public List<MatchResultResponse> runSync(Long positionId) {
        var task = create(positionId, UUID.randomUUID().toString(), true);
        execute(task.view().id(), false);
        var completed = store.get(task.view().id()).view();
        if (!"SUCCEEDED".equals(completed.status())) {
            throw new MatchTaskFailure(completed.errorCode() == null ? "TASK_INACTIVE" : completed.errorCode(),
                    completed.errorMessage() == null ? "匹配任务已终止" : completed.errorMessage());
        }
        return engine.listMatches(positionId);
    }

    /** Workers never inherit the request SecurityContext; ownership travels in the persisted task. */
    public void execute(String id, boolean claim) {
        if (claim && !store.claim(id)) return;
        var task = store.get(id);
        try {
            store.ensureRunning(id);
            authority.before("MATCH",id,task.view().positionId(),task.ownerId());
            engine.runTask(task);
        } catch (com.example.agent.service.company.TaskAuthority.Revoked e) {
            store.fail(id,"AUTH_REVOKED",e.getMessage());
        } catch (MatchTaskFailure e) {
            store.expire();
            store.fail(id, e.code(), e.getMessage());
        } catch (Exception e) {
            log.warn("Match task failed: taskId={} type={}", id, e.getClass().getSimpleName());
            store.fail(id, "EXECUTION_FAILED", "匹配执行失败，原结果保留，请检查服务后重新匹配");
        }
    }

    private MatchTaskStore.Task owned(String id) {
        var task = store.get(id);
        if (task == null) throw notFound();
        requirePosition(task.view().positionId());
        return task;
    }

    private void requirePosition(Long id) {
        access.operator(id,access.user(),false);
    }

    private MatchTaskResponse view(MatchTaskStore.Task task) {
        return task.view().authority(authority.actorId("MATCH",task.view().id(),task.ownerId()),authority.canCancel("MATCH",task.view().id(),task.ownerId()));
    }

    private Long owner() {
        Long owner = SecurityUtils.getCurrentUserId();
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return owner;
    }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
