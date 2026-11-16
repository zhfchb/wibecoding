package com.example.agent.chat.service.impl;

import com.example.agent.chat.service.ChatService;
import com.example.agent.integration.ai.AiGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    private final AiGateway gateway;
    public String chat(String message) { return gateway.chat(message); }
}
