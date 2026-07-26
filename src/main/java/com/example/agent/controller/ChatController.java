package com.example.agent.controller;

import com.example.agent.domain.ChatRequest;
import com.example.agent.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/simple")
    public String simpleChat(@RequestBody ChatRequest request) {
        return chatService.chat(request.getMessage());
    }
}
