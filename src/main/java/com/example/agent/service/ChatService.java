package com.example.agent.service;

public interface ChatService {

    /**
     * 发送消息给 AI 模型并获取回答
     *
     * @param message 用户输入的消息
     * @return AI 模型的回答
     */
    String chat(String message);
}
