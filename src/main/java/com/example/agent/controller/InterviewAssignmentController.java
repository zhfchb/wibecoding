package com.example.agent.controller;

import com.example.agent.service.company.InterviewAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class InterviewAssignmentController {
    private final InterviewAssignmentService interviews;
    public record AssignmentInput(Long interviewerId) {}
    @GetMapping("/interviews/assignments") public List<InterviewAssignmentService.Assignment> mine() { return interviews.mine(); }
    @GetMapping("/interviews/assignments/{id}") public InterviewAssignmentService.Detail detail(@PathVariable Long id) { return interviews.detail(id); }
    @PutMapping("/interviews/assignments/{id}/evaluation") public InterviewAssignmentService.Detail save(@PathVariable Long id,@RequestBody InterviewAssignmentService.EvaluationInput input) { return interviews.save(id,input); }
    @GetMapping("/recruitment/positions/{id}/interviews") public List<InterviewAssignmentService.Assignment> managed(@PathVariable Long id) { return interviews.managed(id); }
    @PostMapping("/recruitment/resumes/{id}/interviews") public InterviewAssignmentService.Assignment assign(@PathVariable Long id,@RequestBody AssignmentInput input) { return interviews.assign(id,input.interviewerId()); }
    @PostMapping("/recruitment/interviews/{id}/revoke") public void revoke(@PathVariable Long id) { interviews.revoke(id); }
}
