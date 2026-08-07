package com.example.agent.service.impl;

import com.example.agent.config.RagProperties;
import com.example.agent.service.AgentService;
import com.example.agent.tools.CalculatorTools;
import com.example.agent.tools.TimeTools;
import com.example.agent.tools.WebSearchTools;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.springframework.stereotype.Service;

/**
 * 基于 LangChain4j AiServices 实现 Agent：
 * LLM 根据用户问题自动决策调用哪个 @Tool，拿到工具返回值后继续推理，直到给出最终答案。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private final Assistant assistant;

    public AgentServiceImpl(OpenAiChatModel chatModel, RagProperties ragProperties) {
        RagProperties.WebSearch webSearch = ragProperties.webSearch();
        Assistant builder = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .tools(new TimeTools(), new CalculatorTools())
                .build();

        // 如果 Web 搜索已启用且配置了 API key，则重新构建带搜索工具的 Agent
        if (webSearch != null && webSearch.enabled()
                && webSearch.apiKey() != null && !webSearch.apiKey().isEmpty()) {
            WebSearchTools searchTools = new WebSearchTools(webSearch);
            this.assistant = AiServices.builder(Assistant.class)
                    .chatModel(chatModel)
                    .tools(new TimeTools(), new CalculatorTools(), searchTools)
                    .build();
        } else {
            this.assistant = builder;
        }
    }

    @Override
    public String chat(String message) {
        return assistant.chat(message);
    }

    /** LangChain4j 动态代理接口，工具调用由框架自动编排；系统提示通过 @SystemMessage 注解注入 */
    interface Assistant {
        @SystemMessage("""
                你是一个智能助手。可以调用以下工具：
                - 时间查询：获取当前日期时间
                - 计算器：执行四则运算
                - 网络搜索：搜索互联网获取实时信息（如新闻、招聘、天气、股价等）

                请根据用户问题自动决策调用哪个工具。当知识库没有相关内容或用户询问实时信息时，应优先调用网络搜索工具。
                其他问题直接回答。""")
        String chat(@UserMessage String message);
    }
}
