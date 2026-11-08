package com.example.agent.controller;
import com.example.agent.service.company.RecruitmentOutcomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequiredArgsConstructor
public class RecruitmentProgressController {
    private final RecruitmentOutcomeService outcomes;
    @GetMapping("/api/v1/recruitment/positions/{id}/progress")
    public List<RecruitmentOutcomeService.Progress> progress(@PathVariable Long id) {return outcomes.progress(id);}
}
