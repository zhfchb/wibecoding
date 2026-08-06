package com.example.agent.controller;

import com.example.agent.config.OpenApiConfig;
import com.example.agent.config.RagProperties;
import com.example.agent.service.MultiAgentService;
import com.example.agent.workflow.multiagent.MultiAgentTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多 Agent 协作控制器（M9）。
 *
 * <p>三 Agent + HITL 审核工作流，采用异步任务模式：
 * <ol>
 *   <li>提交问题 → 返回 taskId（图执行到审核节点前暂停）</li>
 *   <li>轮询任务状态 → 查看草稿与状态</li>
 *   <li>审批接口 → 通过/拒绝（拒绝带反馈触发重生成）</li>
 * </ol>
 */
@Tag(name = "多 Agent 协作", description = "检索 Agent → 生成 Agent → HITL 人工审核工作流")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class MultiAgentController {

    private final MultiAgentService multiAgentService;
    private final RagProperties ragProperties;

    @Operation(summary = "提交多 Agent 任务",
            description = "提交问题后，检索 Agent 找资料、生成 Agent 写草稿，执行到审核节点前暂停。" +
                    "返回任务快照（状态 AWAITING_REVIEW + 草稿）。rag.multi-agent.enabled=false 时返回 501。")
    @PostMapping("/multi-agent")
    public ResponseEntity<?> submit(@RequestBody Map<String, String> body) {
        RagProperties.MultiAgent config = ragProperties.multiAgent();
        if (config == null || !config.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "多 Agent 工作流未启用，请在配置中设置 rag.multi-agent.enabled=true"));
        }
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "question 不能为空"));
        }
        MultiAgentTask task = multiAgentService.submit(question);
        return ResponseEntity.ok(task);
    }

    @Operation(summary = "查询任务状态",
            description = "轮询任务状态：RUNNING / AWAITING_REVIEW / COMPLETED / FAILED。含草稿与最终答案。")
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        try {
            MultiAgentTask task = multiAgentService.getTask(taskId);
            return ResponseEntity.ok(task);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "列出待审核任务",
            description = "查询所有 AWAITING_REVIEW 状态的任务，按创建时间升序。" +
                    "进程重启后可从此接口恢复遗留的待审核任务列表。")
    @GetMapping("/tasks/pending")
    public List<MultiAgentTask> listPendingTasks() {
        return multiAgentService.listPendingTasks();
    }

    @Operation(summary = "审批通过",
            description = "人工审批通过，图 resume 到 END，最终答案即草稿内容。任务状态变为 COMPLETED。")
    @PostMapping("/tasks/{taskId}/approve")
    public ResponseEntity<?> approve(@PathVariable String taskId) {
        try {
            MultiAgentTask task = multiAgentService.approve(taskId);
            return ResponseEntity.ok(task);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "审批拒绝",
            description = "人工审批拒绝，携带反馈，触发生成 Agent 重写草稿。" +
                    "达到 maxRejects 后强制通过。任务状态回到 AWAITING_REVIEW 或 COMPLETED。")
    @PostMapping("/tasks/{taskId}/reject")
    public ResponseEntity<?> reject(@PathVariable String taskId,
                                    @RequestBody(required = false) Map<String, String> body) {
        try {
            String feedback = body != null ? body.getOrDefault("feedback", "") : "";
            MultiAgentTask task = multiAgentService.reject(taskId, feedback);
            return ResponseEntity.ok(task);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
