# Smart Knowledge Agent 项目架构文档

## 1. 项目概述
本项目是一个基于 RAG (检索增强生成) 和 Agent 工具调用的智能知识库系统。
核心理念：业务逻辑由 AI 快速实现，开发者主控系统架构、数据流与工程规范。

## 2. 技术栈选型
*   **核心语言**：Java 17
*   **基础框架**：Spring Boot 3.x
*   **LLM 接入层**：LangChain4j (OpenAI 兼容格式)
*   **大模型**：DeepSeek-V4 (API 接入)
*   **向量数据库**：Qdrant (Docker 部署，没有镜像)
*   **关系型数据库**：MySQL 8.0 (Docker 部署，没有镜像)
*   **构建工具**：Maven

## 3. 项目包结构规范
*   `com.example.agent.config`：系统配置类 (LLM, 跨域, 数据库等)
*   `com.example.agent.controller`：对外暴露的 REST API
*   `com.example.agent.service`：核心业务逻辑与 Agent 组装
*   `com.example.agent.domain`：实体类 (Entity, DTO, VO)
*   `com.example.agent.tools`：Agent 可调用的外部工具包 (Function Calling)

## 4. AI 编码协作原则 (Claude Code 必读)
1.  **先读后写**：在新增复杂模块前，请先查阅本文件，确保不破坏现有包结构和技术栈。
2.  **小步快跑**：每次只完成一个具体功能，编写完成后必须运行 `mvn clean compile` 或 `mvn test` 验证。
3.  **配置分离**：所有敏感的 API Key 必须放在 `application.yml` 中，并提醒开发者在本地进行环境变量覆盖。
4.  **明确依赖**：引入新的 Maven 依赖时，必须说明原因。
