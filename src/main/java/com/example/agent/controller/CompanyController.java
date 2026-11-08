package com.example.agent.controller;

import com.example.agent.service.company.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/company")
@RequiredArgsConstructor
public class CompanyController {
    private final CompanyService companies;
    @GetMapping public CompanyService.Company current() { return companies.current(); }
    @PutMapping public CompanyService.Company update(@RequestBody CompanyService.Update request) { return companies.update(request); }
    @GetMapping("/members") public List<CompanyService.Member> members() { return companies.members(); }
    @PostMapping("/members") public CompanyService.Member add(@RequestBody CompanyService.NewMember request) { return companies.addMember(request); }
}
