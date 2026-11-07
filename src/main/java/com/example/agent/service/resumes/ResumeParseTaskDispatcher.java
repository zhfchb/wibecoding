package com.example.agent.service.resumes;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class ResumeParseTaskDispatcher {
    private final ResumeParseTaskStore store;
    private final ResumeParseTaskService tasks;
    private final ThreadPoolTaskExecutor workers;
    private final boolean enabled;
    public ResumeParseTaskDispatcher(ResumeParseTaskStore store,ResumeParseTaskService tasks,
            @Qualifier("matchTaskExecutor") ThreadPoolTaskExecutor workers,
            @Value("${resume.parse-tasks.dispatch-enabled:true}") boolean enabled) {
        this.store=store;this.tasks=tasks;this.workers=workers;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${resume.parse-tasks.poll-ms:1000}")
    public void dispatch() {
        if (!enabled) return;
        tasks.expire();
        for (var task:store.queued()) {
            try { workers.execute(() -> tasks.execute(task.id())); }
            catch (TaskRejectedException full) { break; }
        }
    }
}
