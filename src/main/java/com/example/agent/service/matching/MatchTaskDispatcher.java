package com.example.agent.service.matching;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.core.task.TaskRejectedException;

@Component
public class MatchTaskDispatcher {
    private final MatchTaskStore store;
    private final MatchTaskService tasks;
    private final ThreadPoolTaskExecutor workers;
    private final boolean enabled;

    public MatchTaskDispatcher(MatchTaskStore store, MatchTaskService tasks,
                               @Qualifier("matchTaskExecutor") ThreadPoolTaskExecutor workers,
                               @Value("${match.tasks.dispatch-enabled:true}") boolean enabled) {
        this.store = store; this.tasks = tasks; this.workers = workers; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${match.tasks.poll-ms:1000}")
    public void dispatch() {
        if (!enabled) return;
        store.expire();
        for (var task : store.queued()) {
            try {
                workers.execute(() -> tasks.execute(task.view().id(), true));
            } catch (TaskRejectedException full) {
                // Unclaimed rows remain queued durably; the next poll retries dispatch, not model execution.
                break;
            }
        }
    }
}
