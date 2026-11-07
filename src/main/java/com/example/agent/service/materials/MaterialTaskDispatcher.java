package com.example.agent.service.materials;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class MaterialTaskDispatcher {
    private final MaterialTaskStore store;
    private final MaterialTaskService tasks;
    private final ThreadPoolTaskExecutor workers;
    private final boolean enabled;
    public MaterialTaskDispatcher(MaterialTaskStore store,MaterialTaskService tasks,
            @Qualifier("matchTaskExecutor") ThreadPoolTaskExecutor workers,
            @Value("${material.tasks.dispatch-enabled:true}") boolean enabled) {
        this.store=store; this.tasks=tasks; this.workers=workers; this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${material.tasks.poll-ms:1000}")
    public void dispatch() {
        if (!enabled) return;
        store.expire();
        for (var task:store.queued()) {
            try { workers.execute(() -> tasks.execute(task.id(),true)); }
            catch (TaskRejectedException full) { break; }
        }
    }
}
