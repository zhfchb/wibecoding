package com.example.agent.domain.chat;

import java.util.List;

public record RagChatResponse(String answer, List<RagSource> sources) {
}
