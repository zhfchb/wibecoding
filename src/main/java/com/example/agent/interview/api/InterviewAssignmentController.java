package com.example.agent.interview.api;

import com.example.agent.interview.service.InterviewAssignmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class InterviewAssignmentController {
    private final InterviewAssignmentService interviews;
    private final com.example.agent.interview.service.InterviewScheduleService schedules;
    @PutMapping("/recruitment/interviews/{id}/schedule") public com.example.agent.interview.service.InterviewScheduleService.Schedule schedule(@PathVariable Long id,@RequestBody com.example.agent.interview.service.InterviewScheduleService.Input input) { return schedules.put(id,input); }
    @PostMapping("/recruitment/interviews/{id}/schedule/state") public com.example.agent.interview.service.InterviewScheduleService.Schedule state(@PathVariable Long id,@RequestBody com.example.agent.interview.service.InterviewScheduleService.Transition input) { return schedules.transition(id,input); }
    @GetMapping("/recruitment/interviews/{id}/schedule/history") public List<com.example.agent.interview.service.InterviewScheduleService.Event> history(@PathVariable Long id) { return schedules.history(id); }
    public record AssignmentInput(Long interviewerId,Integer roundNo) {}
    @GetMapping("/interviews/assignments") public List<InterviewAssignmentService.Assignment> mine() { return interviews.mine(); }
    @GetMapping("/interviews/assignments/{id}") public InterviewAssignmentService.Detail detail(@PathVariable Long id) { return interviews.detail(id); }
    @PutMapping("/interviews/assignments/{id}/evaluation") public InterviewAssignmentService.Detail save(@PathVariable Long id,@RequestBody InterviewAssignmentService.EvaluationInput input) { return interviews.save(id,input); }
    @GetMapping("/recruitment/positions/{id}/interviews") public List<InterviewAssignmentService.Assignment> managed(@PathVariable Long id) { return interviews.managed(id); }
    @PostMapping("/recruitment/resumes/{id}/interviews") public InterviewAssignmentService.Assignment assign(@PathVariable Long id,@RequestBody AssignmentInput input) { return interviews.assign(id,input.interviewerId(),input.roundNo()); }
    @PostMapping("/recruitment/interviews/{id}/revoke") public void revoke(@PathVariable Long id) { interviews.revoke(id); }
}
