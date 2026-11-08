package com.example.agent.controller;
import com.example.agent.service.company.RecruitmentOutcomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/recruitment/resumes/{id}/outcome")
public class RecruitmentOutcomeController {
    private final RecruitmentOutcomeService service;
    @GetMapping public RecruitmentOutcomeService.State get(@PathVariable Long id) {return service.get(id);}
    @GetMapping("/history") public List<RecruitmentOutcomeService.Event> history(@PathVariable Long id) {return service.history(id);}
    @PostMapping public RecruitmentOutcomeService.State change(@PathVariable Long id,@RequestBody RecruitmentOutcomeService.Input input) {return service.change(id,input);}
}
