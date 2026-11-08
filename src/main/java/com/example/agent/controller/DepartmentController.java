package com.example.agent.controller;

import com.example.agent.service.company.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/company/departments")
@RequiredArgsConstructor
public class DepartmentController {
    private final DepartmentService departments;
    public record Status(Boolean enabled) {}
    @GetMapping public List<DepartmentService.Department> list(@RequestParam(defaultValue="false") boolean activeOnly) {
        return departments.list(activeOnly);
    }
    @PostMapping public DepartmentService.Department create(@RequestBody DepartmentService.Input input) { return departments.create(input); }
    @PutMapping("/{id}") public DepartmentService.Department update(@PathVariable Long id,@RequestBody DepartmentService.Input input) { return departments.update(id,input); }
    @PostMapping("/{id}/status") public DepartmentService.Department status(@PathVariable Long id,@RequestBody Status input) { return departments.status(id,input.enabled()); }
}
