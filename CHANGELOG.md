# 变更日志

## 2026-08-04

### 项目大纲升级(面向招聘市场)

把 `ARCHITECTURE.md` 从「功能规划文档」升级为「简历项目导向的工程蓝图」,新增 4 个章节,重写 6 个章节:

**重写章节**
- §1 项目定位:从泛泛的「RAG + Agent 系统」改为「面向招聘市场的 RAG + Agent 智能知识库系统」,明确 4 条设计原则(广度优先 / AI 真实可用 / Java 基本功齐全 / 可量化效果)。
- §2 当前状态:新增 M3 简历增强迭代进度(真实 Embedding ✅ / 评估 ✅ / SSE ✅ / Agent ⚠️);§2.2 规划分 AI 能力深化 / Java 基本功 / 工程化三类。
- §3 技术选型:表格加「状态」列,新增 LLM 流式、Agent、Redis、Spring Security、Swagger、容器化 6 行,每行标注已用/进行中/规划。
- §4 系统分层:从 5 层扩为 7 层(新增基础设施层、安全层)。
- §5 包结构:当前结构补 `tools`;规划扩展补 `entity/mapper/dto/vo/security/common`。
- §6 接口设计:新增 6.3 SSE 流式、6.5 Agent Function Calling;6.6 规划接口补登录、文档分页、对话历史。

**新增章节**
- §10 招聘市场对标:AI 应用岗 + Java 后端岗两张能力对标表,每项标注状态与简历可写话术。
- §11 简历素材:项目完成后的简历段落模板 + 6 个面试可深聊点。
- §12 待办与风险:更新为 M3 编译错误、真实 Embedding 未联调、MySQL 未接入等具体项。
- §13 AI 编码协作原则:新增第 5/6 条(简历导向、如实记录)。

**里程碑重排**:M3 改为「简历增强(Embedding+SSE+Agent)」,新增 M4(MySQL+Redis)、M5(Security+JWT)、M6(Swagger+Docker+CI)、M7(RAG 优化)。

### 简历增强迭代(模块 1-3,进行中)

目标:把项目从「RAG demo」升级为「RAG 项目」,补齐招聘市场要求的 AI 能力广度。

**模块 1:接入真实 Embedding(已完成 ✅)**

- `application.yml`:`rag.embedding` 切换为 BGE-M3 对齐(dimension=1024, model-name=BAAI/bge-m3),默认仍用 hash 保证开箱即跑,配 key 后 `provider=openai` 启用真实语义检索。
- Embedding 走独立 API(硅基流动 https://api.siliconflow.cn/v1),不复用 DeepSeek(DeepSeek 暂无 Embedding 接口)。
- `application-dev.yml`:补充 `rag.embedding.provider=openai` 与 api-key 占位。
- 新增 `RagEvaluationTest`:5 篇技术文档 + 5 个自然语言查询,计算 Recall@K 与 MRR,作为简历效果指标数据来源。
- hash baseline 测试结果:**Recall@3 = 80%(4/5)、MRR = 0.8000**;"RAG 是怎么工作的"未命中,验证 hash 缺乏语义理解,真实 BGE-M3 可补上。

**模块 2:SSE 流式输出(已完成 ✅)**

- `DeepSeekConfig` 新增 `OpenAiStreamingChatModel` Bean(LangChain4j 0.35.0 流式对话模型)。
- `RagChatService` 新增 `streamChat(String)`,返回 `SseEmitter`;`RagChatServiceImpl` 实现:检索→无命中直接推送提示结束→有命中则用 `StreamingResponseHandler<AiMessage>` 回调(`onNext` 推 token、`onComplete(Response<AiMessage>)` 结束、`onError` 错误完成)。
- `ChatController` 新增 `POST /api/v1/chat/stream`(produces=text/event-stream)。
- 关键坑:LangChain4j 0.35.0 的 `StreamingResponseHandler` 方法是 `onNext(String)` / `onComplete(Response<T>)` / `onError(Throwable)`,非 `onPartialResponse` / `onCompleteResponse`。
- `RagChatServiceTest` 同步更新构造函数(新增 streamingModel 参数)。
- 测试:`mvn clean test -Dtest=!QdrantVectorStoreTest` 13 run / 0 failures。

**模块 3:Agent / Function Calling(代码已写,待修复编译错误 ⚠️)**

- `pom.xml` 新增 `langchain4j` 主依赖(AiServices / @Tool 所在包),含传递依赖 opennlp-tools 1.9.4。
- 新增 `tools/TimeTools`(@Tool 获取当前时间)与 `tools/CalculatorTools`(@Tool + @P 四则运算)。
- 新增 `service/AgentService` 与 `service/impl/AgentServiceImpl`:基于 `AiServices.builder()` 动态代理 Assistant 接口,注册两个工具。
- 新增 `controller/AgentController`:`POST /api/v1/agent/chat`。
- 当前阻塞:`AiServices.builder()` 的 `systemMessage(String)` 方法在 0.35.0 不存在,编译失败,待下一轮修复(需确认正确 API 名,可能是 `systemMessageProvider` 或去掉该调用)。
- 未跑测试,模块 3 尚未验证。

### M2 RAG 知识库完成 ✅

**容器与配置**

- `docker compose up -d` 拉起 Qdrant 1.19.0 容器（`wibecoding-qdrant`），6333（HTTP）/6334（gRPC）端口正常映射，`/collections` 接口可访问。
- 新增 `application-test.yml` 测试 profile：dummy DeepSeek Key + `hash` Embedding + `memory` VectorStore，让 `@SpringBootTest` 在无真实 Key 时也能加载上下文。

**测试补齐与验证**

- 修复 `AgentApplicationTests.contextLoads()` 默认 profile 下因 `api-key` 为空导致 `OpenAiChatModel` Bean 创建失败的问题：加 `@ActiveProfiles("test")`。
- 新增 `QdrantVectorStoreTest` 集成测试（3 个用例：`addAndSearchFindsInsertedChunk` / `deleteByDocumentIdRemovesAllChunks` / `searchRespectsTopKLimit`），通过 `@EnabledIfEnvironmentVariable(QDRANT_URL)` 默认跳过、本地手动启用。
- 验证发现并修复测试用例中 point ID 不符合 Qdrant 1.x「UUID 或无符号整数」约束的问题（生产代码 `KnowledgeServiceImpl` 已使用 UUID，仅测试用例需要修正）。
- 测试结果：
  - `mvn clean test`：15 run / 0 failures / 0 errors / 3 skipped（Qdrant 集成测试默认跳过）。
  - `QDRANT_URL=http://localhost:6333 mvn test -Dtest=QdrantVectorStoreTest`：3 run / 0 failures / 0 errors。

**文档同步**

- 更新 `ARCHITECTURE.md`：2.1 节补充 M2 已完成项；2.2 节移除已完成项；3 节向量数据库改为 Qdrant 1.19.x；5 节包结构补 `exception` / `rag` 目录；6 节补充 RAG 对话与知识库导入接口示例；7 节补充 RAG 配置键；8 节更新本地开发与验证流程；9 节 M2 状态改为「已完成」；10 节更新待办与风险。

### M2 RAG 开发（进行中）

- 新增 `docker-compose.yml`，规划 Qdrant 容器与 `6333/6334` 端口映射，数据挂载到命名卷。
- 新增 `RagProperties` / `RagConfig`，支持 `hash` 本地零依赖 Embedding 与 `openai` 兼容 Embedding 切换。
- 实现文档导入：`POST /api/v1/knowledge/upload`，支持 `txt/md/markdown`、大小与格式校验、UTF-8 读取。
- 实现文档切分 `DocumentChunker`：按段落和语句边界切分，支持最大长度与重叠。
- 实现 `LexicalHashEmbeddingModel`：中英文 token 哈希向量，便于本地演示；新增 `VectorStore` 抽象及 `InMemoryVectorStore`、`QdrantVectorStore`。
- 实现 RAG 对话：`POST /api/v1/chat`，检索知识库上下文后调用 DeepSeek 回答，并返回来源片段。
- 增加统一异常响应 `ApiErrorResponse` / `GlobalExceptionHandler`。
- 待完成：Qdrant 容器启动验证、M2 测试与架构文档同步。

## 2026-08-03

### 文档与协作规范

- 将 `ARCHITECTURE.md` 完善为完整项目大纲，补充当前状态、规划模块、接口设计、配置规范、里程碑和待办。
- 新增 `AGENTS.md`，固化“开始任务前阅读项目大纲、每次变更必须记录到 `CHANGELOG.md`”的协作约定。

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
