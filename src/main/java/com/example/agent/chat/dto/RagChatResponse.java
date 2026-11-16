package com.example.agent.chat.dto;

import java.util.List;

public record RagChatResponse(String sessionId, String answer, List<RagSource> sources) {
}
