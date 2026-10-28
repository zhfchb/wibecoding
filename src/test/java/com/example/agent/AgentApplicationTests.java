package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 使用 test profile 加载上下文，使用 H2/hash 和测试服务令牌，启动时不调用外部 AI 服务。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
