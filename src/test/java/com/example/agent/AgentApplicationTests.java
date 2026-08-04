package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 使用 test profile 加载上下文，避免默认 profile 下 deepseek.api-key 为空导致 OpenAiChatModel Bean 创建失败。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
