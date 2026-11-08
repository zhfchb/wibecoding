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
    private final com.example.agent.service.company.InterviewScheduleService schedules;
    @PutMapping("/recruitment/interviews/{id}/schedule") public com.example.agent.service.company.InterviewScheduleService.Schedule schedule(@PathVariable Long id,@RequestBody com.example.agent.service.company.InterviewScheduleService.Input input) { return schedules.put(id,input); }
    @PostMapping("/recruitment/interviews/{id}/schedule/state") public com.example.agent.service.company.InterviewScheduleService.Schedule state(@PathVariable Long id,@RequestBody com.example.agent.service.company.InterviewScheduleService.Transition input) { return schedules.transition(id,input); }
    @GetMapping("/recruitment/interviews/{id}/schedule/history") public List<com.example.agent.service.company.InterviewScheduleService.Event> history(@PathVariable Long id) { return schedules.history(id); }
    public record AssignmentInput(Long interviewerId) {}
    @GetMapping("/interviews/assignments") public List<InterviewAssignmentService.Assignment> mine() { return interviews.mine(); }
    @GetMapping("/interviews/assignments/{id}") public InterviewAssignmentService.Detail detail(@PathVariable Long id) { return interviews.detail(id); }
    @PutMapping("/interviews/assignments/{id}/evaluation") public InterviewAssignmentService.Detail save(@PathVariable Long id,@RequestBody InterviewAssignmentService.EvaluationInput input) { return interviews.save(id,input); }
    @GetMapping("/recruitment/positions/{id}/interviews") public List<InterviewAssignmentService.Assignment> managed(@PathVariable Long id) { return interviews.managed(id); }
    @PostMapping("/recruitment/resumes/{id}/interviews") public InterviewAssignmentService.Assignment assign(@PathVariable Long id,@RequestBody AssignmentInput input) { return interviews.assign(id,input.interviewerId()); }
    @PostMapping("/recruitment/interviews/{id}/revoke") public void revoke(@PathVariable Long id) { interviews.revoke(id); }
}
