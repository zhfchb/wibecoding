package com.example.agent.recruitment.api;

import com.example.agent.recruitment.followup.RecruitmentOutcomeService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class RecruitmentProgressController {
    private final RecruitmentOutcomeService outcomes;
    @GetMapping("/api/v1/recruitment/positions/{id}/progress")
    public List<RecruitmentOutcomeService.Progress> progress(@PathVariable Long id) {return outcomes.progress(id);}
}
