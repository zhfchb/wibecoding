# Smart Knowledge Agent 项目大纲

> 本文档是项目的总纲，作为功能规划、模块拆分、开发顺序和工程规范的统一入口。代码结构与本文档不一致时，以最近一次功能迭代的实际代码为准，并在本文件同步更新。

## 1. 项目定位

本项目定位为**面向招聘市场的 RAG + Agent 智能知识库系统**，目标是覆盖 Java 后端 + AI 应用两条主线的常见技术点，作为简历项目能同时撑住 Java 岗和 AI 应用岗的技术面。

**设计原则**：
- **广度优先**：每个方向做到"能讲清原理 + 有代码支撑 + 能聊优化方向"，不追求单点极致深度。
- **AI 真实可用**：RAG 必须接真实语义 Embedding，不能停留在 hash 哈希演示；Agent 必须有真实 Function Calling，不能只画架构图。
- **Java 后端基本功齐全**：MySQL + ORM + Redis + 认证 + API 文档 + 容器化部署，不缺项。
- **可量化效果**：每个核心能力都有测试或指标数据支撑，面试时拿得出数字。

## 2. 当前状态

### 2.1 已完成

**M1 基础工程 + DeepSeek 单轮对话（2026-07-26）**

- 初始化 Java 17 + Spring Boot 3.3.5 + Maven 工程。
- 接入 LangChain4j 与 DeepSeek OpenAI 兼容接口。
- 提供基础单轮对话接口 `POST /api/v1/chat/simple`。
- 完成 `application.yml` 骨架与 `application-dev.yml` 本地配置分离。

**M2 RAG 知识库（2026-08-04）**

- 知识库文档导入 `POST /api/v1/knowledge/upload`：支持 `txt/md/markdown`，UTF-8 读取，大小与扩展名校验。
- 文档切分 `DocumentChunker`：按段落与语句边界切分，支持最大长度与重叠；空文档返回空列表。
- Embedding 抽象 `LexicalHashEmbeddingModel`：中英文 token 哈希向量，本地零依赖演示；通过 `rag.embedding.provider` 可切换为 `openai` 兼容真实语义 Embedding。
- VectorStore 抽象与两种实现：`InMemoryVectorStore`（开箱即跑）与 `QdrantVectorStore`（基于 Qdrant REST，自动创建集合、按 `document_id` 删除）。
- RAG 对话 `POST /api/v1/chat`：检索知识库上下文后调用 DeepSeek 回答，并返回来源片段与相似度分数。
- 统一异常响应 `ApiErrorResponse` / `GlobalExceptionHandler`，覆盖参数校验与未支持文档类型等错误。
- `docker-compose.yml` 启动 Qdrant 1.19.x，端口 6333（HTTP）/6334（gRPC），数据挂载到命名卷。
- 测试覆盖：`DocumentChunkerTest`、`InMemoryVectorStoreTest`、`LexicalHashEmbeddingModelTest`、`KnowledgeServiceTest`、`RagChatServiceTest`、`AgentApplicationTests`，以及 `QdrantVectorStoreTest`（集成测试，通过 `QDRANT_URL` 环境变量启用）。

**M3 简历增强迭代（2026-08-04，已完成 ✅）**

目标：从「RAG demo」升级为「RAG 项目」，补齐招聘市场要求的 AI 能力广度与 Java 后端基本功。

- ✅ **真实 Embedding 接入**：`rag.embedding` 切换为 BGE-M3 对齐（dimension=1024），默认 hash 保证开箱即跑，配 key 后 `provider=openai` 启用真实语义检索；走独立 API（硅基流动），不复用 DeepSeek。
- ✅ **检索质量评估**：新增 `RagEvaluationTest`，5 篇文档 + 5 个查询，计算 Recall@K 与 MRR。hash baseline：**Recall@3 = 80%、MRR = 0.8000**，作为简历效果指标数据来源。
- ✅ **SSE 流式输出**：`POST /api/v1/chat/stream`，基于 `OpenAiStreamingChatModel` + `SseEmitter` + `StreamingResponseHandler` 实现打字机效果；无命中时直接推送提示结束，不调用 LLM。
- ✅ **Agent / Function Calling**：新增 `tools/TimeTools`、`tools/CalculatorTools`，基于 `AiServices` 动态代理注册工具；新增 `POST /api/v1/agent/chat`。系统提示通过 `@SystemMessage` 注解注入（0.35.0 的 `AiServices.builder()` 无 `systemMessage(String)` 方法）。

**M4 Java 后端基本功（2026-08-04，已完成 ✅）**

- ✅ **MySQL + MyBatis-Plus**：`DocumentEntity`（文档元数据）+ `ChatHistoryEntity`（对话历史）+ 分页插件 + 逻辑删除；`schema.sql` 建表脚本。
- ✅ **Redis 多轮会话**：`SessionStore` 抽象 + `MemorySessionStore`（测试）+ `RedisSessionStore`（生产，JSON + TTL）；`ChatSessionProperties` 配置 `store-type` 切换。
- ✅ **多轮会话改造**：`RagChatServiceImpl` 支持 `sessionId`，加载历史 → 拼 messages → 调 LLM → 保存 user+assistant 到 Redis + MySQL；流式在 `onComplete` 累积完整回答后保存。
- ✅ **文档元数据持久化**：上传文档后写 `document` 表。
- ✅ **对话历史持久化**：每轮对话写 `chat_history` 表。
- ✅ **新增接口**：`GET /api/v1/knowledge/documents`（分页查询文档）、`GET /api/v1/chat/history/{sessionId}`（查询对话历史）。
- ✅ **docker-compose 全栈**：Qdrant + MySQL 8.0 + Redis 7 一键起。
- ✅ **测试**：H2 替代 MySQL、memory session store 替代 Redis，13 测试全过。

**M5 认证授权：Spring Security + JWT（2026-08-05，已完成 ✅）**

- ✅ **RBAC 两角色**：`sys_user` 表（username/password/role/deleted），单字段 role 存 ADMIN/USER，@TableLogic 逻辑删除。
- ✅ **JWT 认证**：`JwtTokenProvider`（jjwt 0.12.6，HS256 签名，claim 含 userId/username/role）+ `JwtAuthenticationFilter`（解析 Authorization Bearer 头，写入 SecurityContext）。
- ✅ **Spring Security 配置**：无状态 JWT（禁用 session/csrf），`/api/v1/auth/register`+`/api/v1/auth/login` 公开，`/api/v1/admin/**` 需 ADMIN 角色，其他 `/api/v1/**` 需登录。
- ✅ **密码加密**：BCryptPasswordEncoder（强度 10）。
- ✅ **认证接口**：`POST /api/v1/auth/register`（注册，默认 USER 角色，@Valid 校验）+ `POST /api/v1/auth/login`（登录，AuthenticationManager 校验密码）+ `GET /api/v1/auth/me`（查当前用户）。
- ✅ **管理接口**：`GET /api/v1/admin/users`（查所有用户，仅 ADMIN）。
- ✅ **异常处理增强**：`BusinessException`（业务异常 400）+ `BadCredentialsException`（密码错误 401）+ `MethodArgumentNotValidException`（@Valid 校验失败 400）+ `JwtAuthenticationEntryPoint`（未认证 401 JSON）+ `JwtAccessDeniedHandler`（权限不足 403 JSON）。
- ✅ **测试**：`AuthControllerTest` 10 个用例覆盖注册成功/用户名重复/参数校验/登录成功/密码错误/JWT 鉴权/RBAC 角色控制，全量 23 测试通过。
- ✅ **本地初始化**：`scripts/init-users.sql` 插入默认 admin/admin123（ADMIN）+ user/user123（USER）。

**M6 工程化：Swagger + Dockerfile + 全栈 docker-compose + CI（2026-08-05，已完成 ✅）**

- ✅ **Swagger / OpenAPI 3**：springdoc-openapi v2.6.0,`OpenApiConfig` 配置 JWT Bearer 认证 scheme,5 个 Controller 全部加 `@Tag`/`@Operation`/`@SecurityRequirement` 注解。访问 `/swagger-ui.html` 即可在线调试接口,右上角 Authorize 填 JWT 后可测需登录接口。
- ✅ **Security 放行**:`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` 公开访问。
- ✅ **Dockerfile**:多阶段构建(Stage 1 Maven 构建 + Stage 2 JRE alpine 运行),非 root 用户,时区 Asia/Shanghai,健康检查,prod profile。
- ✅ **全栈 docker-compose.prod.yml**:app + qdrant + redis + mysql 一键部署,MySQL 带 healthcheck + 自动建表(schema.sql + init-users.sql 挂载到 `/docker-entrypoint-initdb.d/`)。
- ✅ **application-prod.yml**:所有敏感配置走环境变量(`${MYSQL_PASSWORD}` / `${JWT_SECRET}` / `${DEEPSEEK_CLIENT_API_KEY}` 等),不入仓库。
- ✅ **.env.prod.example**:生产环境配置模板,`.env.prod` 在 .gitignore 中。
- ✅ **.dockerignore**:排除 target/.idea/.git/敏感配置/文档,加速构建 + 防泄露。
- ✅ **GitHub Actions CI**:`.github/workflows/ci.yml`,push/PR 触发编译+测试+打包+上传 jar artifact。
- ✅ **测试**:`mvn test` 23 测试全过,Swagger 集成不破坏现有功能。

### 2.2 规划中

**AI 能力深化**：
- RAG 优化：BM25 + 向量混合检索、rerank（BGE-Reranker）。
- Agent 增强：更多工具（数据库查询、天气、知识库检索）、多轮工具编排。
- LangGraph4j 复杂工作流：自适应 RAG（查询重写循环）、多 Agent 协作、状态持久化、Human-in-the-loop。

**Java 后端基本功补齐**：
- MySQL 8.0 + MyBatis-Plus：文档元数据、对话历史、用户表。
- Redis：多轮会话上下文、限流。
- Spring Security + JWT：用户登录与接口鉴权。
- Swagger/OpenAPI（springdoc）：接口文档。
- Dockerfile + 全栈 docker-compose：一键部署应用 + Qdrant + MySQL + Redis。

**工程化**：
- 入参校验（`@Valid`）、分页、日志规范。
- GitHub Actions CI。

## 3. 技术选型

| 层次 | 选型 | 状态 |
| --- | --- | --- |
| 语言 | Java 17 | 已用 |
| 框架 | Spring Boot 3.3.5 | 已用 |
| LLM 对话 | LangChain4j 0.35.0 + DeepSeek（OpenAI 兼容） | 已用 |
| LLM 流式 | LangChain4j `OpenAiStreamingChatModel` + SSE | 已用 |
| Agent | LangChain4j `AiServices` + `@Tool` Function Calling | 已用 |
| Agent 编排 | LangGraph4j 1.8.x（有状态图、循环、多 Agent 协作、HITL） | 规划 |
| Embedding | BGE-M3（硅基流动 OpenAI 兼容）/ hash（本地演示） | 已用 |
| 向量数据库 | Qdrant 1.19.x（docker compose） | 已用 |
| 关系型数据库 | MySQL 8.0 + MyBatis-Plus 3.5.7 | 已用 |
| 缓存 | Redis 7（会话上下文、TTL 过期） | 已用 |
| 认证 | Spring Security 6 + JWT（jjwt 0.12.6，HS256）+ BCrypt | 已用 |
| API 文档 | springdoc-openapi（Swagger） | 规划 |
| 容器化 | Docker + docker-compose（全栈一键起） | 规划 |
| 构建 | Maven | 已用 |

## 4. 系统分层

- **API 层**：`controller`，接收请求、校验入参、返回结果（含 SSE 流式响应）。
- **业务层**：`service`，会话、检索、Agent 编排、知识库管理。
- **接入层**：`config`，LLM、流式模型、向量库、Embedding、数据库、Redis、Security 等外部组件 Bean。
- **数据层**：`domain`（DTO/VO）+ `entity`（数据库实体）+ `mapper`（MyBatis-Plus 数据访问）。
- **基础设施层**：`rag`（切分、Embedding、向量存储）+ `tools`（Agent 工具）+ `exception`（全局异常）+ `common`（通用工具、响应封装）。
- **安全层**：`security`（规划），Spring Security 配置、JWT 过滤器、用户认证。

## 5. 包结构

当前：

```text
src/main/java/com/example/agent
├── AgentApplication.java
├── config/       # DeepSeek（含流式）与 RAG（Embedding / VectorStore / Chunker）配置
├── controller/   # REST 接口（chat / knowledge / agent）
├── domain/       # 请求/响应模型（chat / knowledge）
├── exception/    # 全局异常与统一错误响应
├── rag/          # RAG 基础设施：切分、Embedding、向量存储抽象与实现
├── service/      # 业务接口与实现（chat / ragChat / knowledge / agent）
└── tools/        # Agent 工具（TimeTools / CalculatorTools）
```

规划扩展：

```text
├── entity/       # 数据库实体（Document / ChatHistory / User）
├── mapper/       # MyBatis-Plus 数据访问
├── dto/          # 接口入参 DTO（带 @Valid 校验）
├── vo/           # 视图模型（分页响应、统一封装）
├── security/     # Spring Security 配置、JWT 过滤器、UserDetails
├── workflow/     # LangGraph4j 图编排（自适应 RAG、多 Agent 协作、状态机）
└── common/       # 通用响应封装、分页、常量、工具类
```

## 6. 接口设计

### 6.1 基础对话

`POST /api/v1/chat/simple`

请求：

```json
{"message": "你好"}
```

响应：

```text
AI 返回文本
```

### 6.2 RAG 对话（同步）

`POST /api/v1/chat`

请求：

```json
{"message": "Spring Boot 怎么配置？"}
```

响应：

```json
{
  "answer": "AI 基于知识库内容生成的回答",
  "sources": [
    {"text": "命中的知识库片段", "score": 0.91}
  ]
}
```

知识库为空或无命中时，返回固定提示文案与空 `sources`，不调用 LLM。

### 6.3 RAG 对话（流式 SSE）

`POST /api/v1/chat/stream`（`produces=text/event-stream`）

请求：同 6.2。

响应：`SseEmitter` 逐 token 推送，前端按 `data:` 事件渲染打字机效果；无命中时推送提示后立即结束，不调用 LLM。

适用场景：前端需要打字机效果、降低首字延迟。

### 6.4 知识库导入

`POST /api/v1/knowledge/upload`（`multipart/form-data`，字段名 `file`）

响应：

```json
{
  "documentId": "UUID",
  "fileName": "spring-boot.md",
  "chunkCount": 3
}
```

### 6.5 Agent 对话（Function Calling）

`POST /api/v1/agent/chat`

请求：

```json
{"message": "现在几点？再帮我算一下 123 乘以 456"}
```

响应：

```text
AI 返回文本（内部自动调用 TimeTools / CalculatorTools）
```

LLM 根据用户问题自动决策调用哪个 `@Tool`，拿到工具返回值后继续推理，直到给出最终答案。

### 6.6 认证授权接口

`POST /api/v1/auth/register`（公开）

请求：

```json
{"username": "alice", "password": "alice123"}
```

响应：

```json
{
  "token": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "userId": 3,
  "username": "alice",
  "role": "USER"
}
```

`POST /api/v1/auth/login`（公开）

请求：同 register。响应：同 register(返回 JWT)。

`GET /api/v1/auth/me`(需登录)

响应:同 register(token 为 null,返回当前登录用户信息)。

`GET /api/v1/admin/users`(需 ADMIN 角色)

响应:所有用户列表。

### 6.7 规划接口

- `GET /api/v1/tools`:查看可用 Agent 工具列表。

## 7. 配置规范

- `application.yml`：提交到仓库，只放默认骨架。
- `application-dev.yml`：本地开发配置，包含真实 Key，已加入 `.gitignore`。
- 生产环境通过环境变量覆盖，例如 `DEEPSEEK_CLIENT_API_KEY`、`RAG_EMBEDDING_API_KEY`。
- `application-test.yml`：测试 profile，提供 dummy Key 与本地 Embedding/VectorStore，让 `@SpringBootTest` 在无真实 Key 时也能加载上下文。
- 当前 DeepSeek 配置键：
  - `deepseek.client.api-key`
  - `deepseek.client.base-url`
- RAG 配置键（前缀 `rag`）：
  - `rag.embedding.provider`：`hash`（本地零依赖）/ `openai`（真实语义）
  - `rag.embedding.dimension`：向量维度（BGE-M3 为 1024）
  - `rag.embedding.model-name`：模型名（默认 `BAAI/bge-m3`）
  - `rag.embedding.base-url` / `rag.embedding.api-key`：独立 Embedding API（硅基流动）
  - `rag.chunking.max-chars` / `rag.chunking.overlap-chars`：切分参数
  - `rag.retrieval.top-k` / `rag.retrieval.min-score`：检索参数
  - `rag.vector-store.type`：`memory` / `qdrant`
  - `rag.vector-store.qdrant-url` / `rag.vector-store.collection-name`：Qdrant 连接
  - `rag.upload.max-file-size-bytes` / `rag.upload.allowed-extensions`：上传校验
- 规划配置键：
  - `spring.datasource.*`：MySQL 连接
  - `spring.data.redis.*`：Redis 连接
  - `jwt.secret` / `jwt.expiration-ms`：JWT 配置(已用,HS256 签名 + 24h 过期)

## 8. 本地开发与验证

```bash
# 启动 Qdrant 容器（首次或每次开机后）
docker compose up -d

# 使用本地 dev 配置启动应用
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 运行单元测试与 Spring 上下文测试（无需真实 Key，使用 test profile）
mvn clean test

# 运行 Qdrant 集成测试（需先 docker compose up -d 启动 Qdrant）
# PowerShell
$env:QDRANT_URL="http://localhost:6333"; mvn test "-Dtest=QdrantVectorStoreTest"
# bash
QDRANT_URL=http://localhost:6333 mvn test -Dtest=QdrantVectorStoreTest

# 运行 RAG 检索质量评估测试（输出 Recall@K 与 MRR 报告）
mvn test "-Dtest=RagEvaluationTest"
```

说明：
- `AgentApplicationTests` 使用 `@ActiveProfiles("test")` 加载 `application-test.yml`，因此 `mvn test` 不需要真实 DeepSeek Key。
- Qdrant 集成测试通过 `@EnabledIfEnvironmentVariable(QDRANT_URL)` 默认跳过，避免 CI 在没有 Qdrant 时失败。
- `RagEvaluationTest` 输出 Recall@3 / MRR 报告，作为简历效果指标数据来源；配真实 Embedding key 后可对比 hash vs BGE-M3 效果。

## 9. 里程碑

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| M1 | 基础工程 + DeepSeek 单轮对话 | 已完成 |
| M2 | RAG 知识库：导入、切分、Embedding、Qdrant 检索 | 已完成 |
| M3 | 简历增强：真实 Embedding + SSE 流式 + Agent Function Calling | 已完成 |
| M4 | Java 后端基本功：MySQL + MyBatis-Plus + Redis 多轮会话 | 已完成 |
| M5 | 认证授权：Spring Security + JWT | 已完成 |
| M6 | 工程化：Swagger + Dockerfile + 全栈 docker-compose + CI | 已完成 |
| M7 | RAG 优化：混合检索 + rerank + 效果对比 | 规划 |
| M8 | LangGraph4j 复杂 Agent：自适应 RAG（查询重写循环）+ 多 Agent 协作 + Human-in-the-loop | 规划 |

## 10. 招聘市场对标

本节用于自检项目是否覆盖招聘 JD 常见要求，避免简历项目缺项。

### 10.1 AI 应用岗能力对标

| 能力点 | 当前状态 | 简历可写 |
| --- | --- | --- |
| RAG 全流程（切分→Embedding→检索→生成） | ✅ 已完成 | "实现 RAG 全流程，支持 txt/md" |
| 真实语义 Embedding（BGE-M3） | ✅ 已接入 | "基于 BGE-M3 实现语义检索" |
| 向量数据库（Qdrant） | ✅ 已完成 | "基于 Qdrant 实现向量存储与检索" |
| 检索质量评估（Recall@K / MRR） | ✅ 已完成 | "构建评测集，Recall@3 达 80%（hash baseline）" |
| 流式输出（SSE） | ✅ 已完成 | "基于 SSE 实现流式响应，打字机效果" |
| Agent / Function Calling | ✅ 已完成 | "基于 Function Calling 实现 Agent 工具编排" |
| 混合检索 + rerank | 🔲 规划 | 待 M7 |
| 多轮会话 + 上下文管理 | ✅ 已完成 | "Redis 维护多轮会话上下文，支持历史拼接" |
| LangGraph4j 有状态图编排（循环/多 Agent/HITL） | 🔲 规划 | 待 M8 |

### 10.2 Java 后端岗能力对标

| 能力点 | 当前状态 | 简历可写 |
| --- | --- | --- |
| Spring Boot 3 + Java 17 | ✅ 已完成 | "基于 Spring Boot 3 + Java 17 构建" |
| RESTful API 设计 | ✅ 已完成 | "设计并实现 6+ RESTful 接口" |
| 全局异常处理 + 入参校验 | ✅ 已完成 | "全局异常处理 + @Valid 入参校验 + 401/403 JSON 响应" |
| MySQL + ORM | ✅ 已完成 | "MyBatis-Plus 持久化文档元数据与对话历史" |
| Redis 缓存 | ✅ 已完成 | "Redis 维护多轮会话上下文，TTL 自动过期" |
| 认证授权（Spring Security + JWT） | ✅ 已完成 | "Spring Security + JWT 实现 RBAC 用户认证与接口鉴权" |
| API 文档（Swagger） | ✅ 已完成 | "springdoc-openapi v2 自动生成 OpenAPI 3 文档 + Swagger UI,支持 JWT Bearer 认证调试" |
| 容器化部署（Docker） | ✅ 已完成 | "多阶段 Dockerfile(jdk17 + alpine) + 全栈 docker-compose.prod.yml(app+qdrant+redis+mysql) + 健康检查 + 非 root 运行" |
| CI/CD | ✅ 已完成 | "GitHub Actions:push/PR 触发编译+测试+打包+artifact 上传" |
| 单元测试 + 集成测试 | ✅ 已完成 | "15+ 测试用例，覆盖单元与集成测试" |

## 11. 简历素材

项目完成后，简历项目段落可直接参考以下表述（按完成度挑选）：

> 基于 Spring Boot 3 + LangChain4j + Qdrant 构建智能知识库系统：
> ① 设计文档切分与语义 Embedding（BGE-M3）管线，支持 txt/md 上传，自定义切分策略（段落+语句边界、最大长度+重叠）；
> ② 实现 Qdrant 向量存储与检索，构建评测集验证检索质量，Recall@3 达 80%（hash baseline），真实 Embedding 下进一步提升；
> ③ 基于 Function Calling 实现 Agent 工具编排（时间查询、计算器），LLM 自动决策调用工具；
> ④ 基于 SSE 实现流式响应，打字机效果降低首字延迟；
> ⑤ MySQL + MyBatis-Plus 持久化文档元数据与对话历史，Redis 维护多轮会话上下文；
> ⑥ Spring Security + JWT 实现用户认证与接口鉴权；
> ⑦ Docker Compose 一键部署全栈（应用 + Qdrant + MySQL + Redis）；
> ⑧ 基于 LangGraph4j 实现自适应 RAG，检索质量不达标时自动重写查询重试，支持人工审批高风险问题。

面试可深聊点（每个点都有代码支撑）：
- RAG 切分策略为什么这样设计？重叠窗口的作用？
- hash vs 真实 Embedding 检索效果差异？如何评估？
- SSE 流式响应的实现原理？为什么不用 WebSocket？
- Function Calling 的执行流程？LLM 如何决策调用工具？
- Qdrant 的 point ID 为什么必须是 UUID？
- 多轮会话上下文如何在 Redis 中存储与淘汰？
- JWT 认证流程？为什么用无状态 JWT 不用 session？token 过期怎么处理？
- BCrypt 密码加密原理？为什么不用 MD5？盐的作用？
- RBAC 角色权限怎么设计？Spring Security 的 hasRole 和 hasAuthority 区别？
- Swagger/OpenAPI 3 怎么集成?springdoc v2 和老牌 springfox 区别?生产环境要不要关 Swagger?
- Dockerfile 多阶段构建的好处?为什么用 alpine + JRE 而不是 JDK?
- docker-compose 的 healthcheck 和 depends_on condition 区别?为什么 MySQL 要等 healthy 才起 app?
- GitHub Actions CI 流程?怎么缓存 Maven 依赖加速?
- 生产环境敏感配置怎么管理?为什么用环境变量不写死在 yml?
- LangChain4j vs LangGraph4j 的定位差异？什么场景该引入 LangGraph？
- 自适应 RAG 的查询重写循环如何实现？状态如何在节点间传递？
- LangGraph4j 的 Checkpoint 机制如何实现断点恢复？

## 12. 待办与风险

- **M3 Agent 编译错误**：✅ 已修复（2026-08-04）。`AiServices.builder()` 在 0.35.0 无 `systemMessage(String)` 方法，改用 `@SystemMessage` 注解注入系统提示。
- **真实 Embedding 未联调**：hash baseline 已验证，但未用真实 BGE-M3 key 跑过端到端，需配硅基流动 key 后验证检索效果提升。
- **MySQL 未接入**：✅ 已完成（2026-08-04）。M4 接入 MySQL 8.0 + MyBatis-Plus，文档元数据与对话历史已持久化。
- **单轮问答**：✅ 已解决（2026-08-04）。M4 接入 Redis 多轮会话，支持 sessionId + 历史拼接。
- **`QdrantVectorStore` ID 约束**：要求 chunk ID 为 UUID 或无符号整数，调用方需遵守（`KnowledgeServiceImpl` 已使用 UUID）。
- **`user` 表保留字冲突**：✅ 已解决(2026-08-05)。H2/MySQL 中 `user` 是保留字,表名改用 `sys_user` 避免冲突。
- **LangGraph4j 学习曲线**：图编排思维（节点+边+共享状态）与线性 Agent 差异大，M8 需预留学习时间；建议先做自适应 RAG 单循环场景验证，再扩展多 Agent。
- **LangGraph4j 版本兼容**：需确认 1.8.x 与 Spring Boot 3.3.5 + LangChain4j 0.35.0 的版本兼容性，避免依赖冲突。
- **`doc/ARCHITECTURE.md` 规划项与代码实现需要随迭代同步更新**。

## 13. AI 编码协作原则

1. **先读后写**：在新增复杂模块前，先查阅本文件，确保不破坏现有包结构和技术栈。
2. **小步快跑**：每次只完成一个具体功能，编写完成后运行 `mvn clean compile` 或 `mvn test` 验证。
3. **配置分离**：所有敏感 API Key 只放在本地配置或环境变量中，不提交到仓库。
4. **明确依赖**：引入新的 Maven 依赖时，必须说明原因并同步更新技术选型。
5. **简历导向**：每个功能点都要思考"面试官会怎么问"，确保有代码支撑、能讲原理、能聊优化。
6. **如实记录**：已完成、进行中、规划中要如实标注，CHANGELOG 记录真实状态，不虚报进度。
