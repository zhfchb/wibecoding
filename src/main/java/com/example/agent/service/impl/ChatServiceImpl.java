package com.example.agent.service.impl;
import com.example.agent.service.AiGateway;
import com.example.agent.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    private final AiGateway gateway;
    public String chat(String message) { return gateway.chat(message); }
}
