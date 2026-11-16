package com.example.agent;

import com.example.agent.chat.config.ChatSessionProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ChatSessionProperties.class)
@MapperScan({"com.example.agent.security.mapper", "com.example.agent.recruitment.mapper",
        "com.example.agent.interview.mapper", "com.example.agent.knowledge.mapper", "com.example.agent.chat.mapper"})
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
