package com.example.agent.controller;

import com.example.agent.service.company.PositionCollaborationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/recruitment/positions/{positionId}/collaborators")
public class PositionCollaborationController {
    private final PositionCollaborationService collaboration;
    public record Grant(Long userId,String permission) {}
    @GetMapping public List<PositionCollaborationService.Viewer> list(@PathVariable Long positionId) { return collaboration.list(positionId); }
    @PutMapping public void grant(@PathVariable Long positionId,@RequestBody Grant input) { collaboration.grant(positionId,input.userId(),input.permission()==null?"VIEWER":input.permission()); }
    @DeleteMapping("/{userId}") public void revoke(@PathVariable Long positionId,@PathVariable Long userId) { collaboration.revoke(positionId,userId); }
}
