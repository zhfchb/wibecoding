# 变更日志

## 2026-07-26

### 项目初始化里程碑 ✅

完成了 Smart Knowledge Agent 项目从零到一的基础搭建：

1. **确立全局架构规范**
   - 编写了 `ARCHITECTURE.md`，明确了项目定位（RAG + Agent 工具调用的智能知识库系统）
   - 确定了技术栈：Java 17 + Spring Boot 3.x + LangChain4j + DeepSeek-V4
   - 规范了包结构（config / controller / service / domain / tools）
   - 制定了 AI 编码协作原则（先读后写、小步快跑、配置分离、明确依赖）

2. **初始化 Spring Boot 3 工程**
   - 使用 Spring Boot 3.3.5 + Maven 搭建项目骨架
   - 引入了 LangChain4j (`langchain4j-open-ai` 0.35.0) 作为 LLM 接入层
   - 配置了 DeepSeek API（OpenAI 兼容格式）对接
   - 实现了配置分离：`application.yml`（骨架）+ `application-dev.yml`（开发环境 Key）

3. **打通 DeepSeek 接口，实现基础对话 API**
   - `DeepSeekConfig` — LLM 客户端 Bean 配置
   - `ChatController` — `POST /api/v1/chat/simple` 端点
   - `ChatService` — 核心对话逻辑
   - 经实测，中英文对话均正常返回，DeepSeek-V4 接口链路完整通畅
