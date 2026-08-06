package com.example.agent.service.impl;

import com.example.agent.service.AgentService;
import com.example.agent.tools.CalculatorTools;
import com.example.agent.tools.TimeTools;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import org.springframework.stereotype.Service;

/**
 * 基于 LangChain4j AiServices 实现 Agent：
 * LLM 根据用户问题自动决策调用哪个 @Tool，拿到工具返回值后继续推理，直到给出最终答案。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private final Assistant assistant;

    public AgentServiceImpl(OpenAiChatModel chatModel) {
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .tools(new TimeTools(), new CalculatorTools())
                .build();
    }

    @Override
    public String chat(String message) {
        return assistant.chat(message);
    }

    /** LangChain4j 动态代理接口，工具调用由框架自动编排；系统提示通过 @SystemMessage 注解注入 */
    interface Assistant {
        @SystemMessage("你是一个智能助手。可以调用工具回答关于时间和计算的问题，其他问题直接回答。")
        String chat(String message);
    }
}
