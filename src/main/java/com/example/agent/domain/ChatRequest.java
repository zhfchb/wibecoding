package com.example.agent.domain;

import lombok.Data;

@Data
public class ChatRequest {
    private String message;
    /** 会话 ID，可选。为空时服务端生成新会话 ID 并在响应中返回。 */
    private String sessionId;
}
