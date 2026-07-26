package com.example.agent.service.impl;

import com.example.agent.service.ChatService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final OpenAiChatModel openAiChatModel;

    @Override
    public String chat(String message) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(message))
                .build();
        return openAiChatModel.chat(request)
                .aiMessage()
                .text();
    }
}
