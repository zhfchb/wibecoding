package com.example.agent.controller;

import com.example.agent.service.company.CandidateNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/recruitment/resumes/{id}/notes")
public class CandidateNoteController {
    private final CandidateNoteService notes;
    @GetMapping public CandidateNoteService.Page list(@PathVariable Long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return notes.list(id,page,size); }
    @PostMapping public void append(@PathVariable Long id,@RequestBody CandidateNoteService.Input input) { notes.append(id,input); }
}
