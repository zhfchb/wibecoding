# 变更日志

## 2026-08-05

### M6 工程化:Swagger + Dockerfile + 全栈 docker-compose + CI 完成 ✅

引入 springdoc-openapi v2 自动生成 API 文档,多阶段 Dockerfile 容器化打包,全栈 docker-compose.prod.yml 一键部署,GitHub Actions CI 自动化构建测试。

**Swagger / OpenAPI 3**
- `pom.xml` 加 `springdoc-openapi-starter-webmvc-ui` 2.6.0。
- `OpenApiConfig`:配置 API 元信息(标题/描述/版本/联系人/协议)+ JWT Bearer 认证 scheme,Swagger UI 右上角 Authorize 填 token 即可测需登录接口。
- `application.yml` 加 `springdoc` 配置(api-docs 路径、swagger-ui 路径、排序规则、扫描包)。
- 5 个 Controller(Auth/Chat/Knowledge/Agent/Admin)全部加 `@Tag`(分组)+ `@Operation`(接口说明)+ `@SecurityRequirement`(标记需登录)注解。
- `SecurityConfig` 放行 `/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`。

**Dockerfile(多阶段构建)**
- Stage 1:`maven:3.9-eclipse-temurin-17` 构建,先拷 pom.xml 利用缓存下依赖,再拷源码打包。
- Stage 2:`eclipse-temurin:17-jre-alpine` 运行,只拷 jar,装时区,非 root 用户(appuser),健康检查,prod profile。
- 镜像体积小(无 JDK 无源码),启动快,安全。

**全栈 docker-compose.prod.yml**
- 4 个服务:app + qdrant + redis + mysql,一键 `docker compose -f docker-compose.prod.yml up -d --build`。
- MySQL 带 healthcheck(mysqladmin ping),app `depends_on.mysql.condition: service_healthy`,等 MySQL 健康再启动。
- MySQL 首次启动自动建表:`schema.sql` + `init-users.sql` 挂载到 `/docker-entrypoint-initdb.d/`。
- app 通过 `env_file: .env.prod` 读敏感配置。

**application-prod.yml**
- 所有敏感配置走环境变量:`${DEEPSEEK_CLIENT_API_KEY}` / `${JWT_SECRET}` / `${MYSQL_PASSWORD}` / `${RAG_EMBEDDING_API_KEY}` 等。
- 默认值仅用于提示,生产必须覆盖。
- RAG 默认用 openai Embedding + qdrant 向量库(生产配置)。
- `springdoc.swagger-ui.enabled` 可通过 `SWAGGER_ENABLED` 环境变量控制生产是否暴露。

**.env.prod.example + .dockerignore + .gitignore**
- `.env.prod.example`:生产配置模板,`.env.prod` 加入 .gitignore 不入仓库。
- `.dockerignore`:排除 target/.idea/.git/敏感配置/文档,加速构建 + 防泄露。
- `.gitignore` 加 `.env.prod` / `.env.local`。

**GitHub Actions CI**
- `.github/workflows/ci.yml`:push/PR 到 main/master 触发。
- 步骤:Checkout → Setup JDK 17(temurin + maven 缓存)→ 编译 → 测试(test profile)→ 打包 → 上传 jar artifact(保留 7 天)。
- README 可加状态徽章:`![CI](https://github.com/<owner>/<repo>/actions/workflows/ci.yml/badge.svg)`。

**测试**
- `mvn test -Dtest=!QdrantVectorStoreTest`:**Tests run: 23, Failures: 0, Errors: 0, Skipped: 0**,BUILD SUCCESS。Swagger 集成不破坏现有功能。

**M6 状态**:规划 → 已完成 ✅。Swagger API 文档 + Dockerfile 容器化 + 全栈 docker-compose + GitHub Actions CI 全部打通。

### M5 认证授权:Spring Security + JWT 完成 ✅

引入 Spring Security 6 + JWT 实现用户认证与接口鉴权,补齐 Java 后端招聘必备的认证授权能力。RBAC 两角色(ADMIN/USER),业务接口需登录,管理接口需 ADMIN。

**依赖新增**
- `spring-boot-starter-security`:Spring Security 6。
- `spring-boot-starter-validation`:`@Valid` / `@NotBlank` / `@Size` 入参校验。
- `jjwt-api` / `jjwt-impl` / `jjwt-jackson` 0.12.6:JWT 生成与校验(HS256)。
- `spring-security-test`(test):安全测试支持。

**数据库**
- `schema.sql` 新增 `sys_user` 表(id/username/password/role/created_at/deleted),表名用 `sys_user` 避免 `user` 在 H2/MySQL 中的保留字冲突。
- `UserEntity` + `UserMapper`:用户实体与数据访问,@TableLogic 逻辑删除。

**security 包(6 个类)**
- `JwtTokenProvider`:JWT 生成与校验,jjwt 0.12.6 HS256,claim 含 userId/username/role。
- `JwtAuthenticationFilter`:OncePerRequestFilter,解析 Authorization Bearer 头,合法则写入 SecurityContext。
- `CustomUserDetailsService`:从 MySQL 加载用户,角色转成 ROLE_ 前缀权限。
- `SecurityConfig`:无状态 JWT(禁用 session/csrf),register/login 公开,admin/** 需 ADMIN,/api/v1/** 需登录,BCryptPasswordEncoder,AuthenticationManager。
- `JwtAuthenticationEntryPoint`:未认证 401 统一返回 JSON。
- `JwtAccessDeniedHandler`:权限不足 403 统一返回 JSON。

**接口**
- `POST /api/v1/auth/register`(公开):注册,默认 USER 角色,@Valid 校验用户名(3-32)+密码(6-64)。
- `POST /api/v1/auth/login`(公开):登录,AuthenticationManager 校验密码,通过后签发 JWT。
- `GET /api/v1/auth/me`(需登录):查当前登录用户信息。
- `GET /api/v1/admin/users`(需 ADMIN):查所有用户列表,验证 RBAC。

**异常处理增强**
- `BusinessException`:业务异常(用户名占用等)返回 400。
- `BadCredentialsException`:密码错误返回 401。
- `MethodArgumentNotValidException`:@Valid 校验失败返回 400 + 所有字段错误信息。
- `GlobalExceptionHandler` 新增 3 个 handler。

**配置**
- `application.yml` 新增 `jwt.secret`(HS256 密钥,至少 32 字符)+ `jwt.expiration-ms`(24 小时)。
- `JwtProperties` 配置类。

**本地初始化**
- `scripts/init-users.sql`:插入默认 admin/admin123(ADMIN)+ user/user123(USER),密码用 BCrypt(强度 10)加密。
- 本地 MySQL 已建 sys_user 表 + 插入两个默认用户。

**测试**
- `AuthControllerTest` 10 个用例:注册成功/用户名重复/参数校验/登录成功/密码错误/无 token 401/有 token 200/USER 访问 admin 403/ADMIN 访问 admin 200/admin 无 token 401。
- `mvn test -Dtest=!QdrantVectorStoreTest`:**Tests run: 23, Failures: 0, Errors: 0, Skipped: 0**,全部通过(含新增 10 个)。

**M5 状态**:规划 → 已完成 ✅。RBAC 用户认证 + JWT 无状态鉴权 + 接口权限控制 + 全局异常处理全部打通。

### 新增 PowerShell 命令执行约定(沉淀踩过的符号陷阱)

本机为 Windows + PowerShell 环境,本次会话又因 PowerShell 符号问题踩坑(`"path\exe.exe" --version` 报错 `Unexpected token`,改用 `& "path\exe.exe" --version` 才行)。用户要求沉淀下来避免重复犯错。

**`doc/AGENTS.md` 新增「PowerShell 命令执行约定」章节**(8 条规则 + 自检清单):
1. 调用带空格/特殊字符路径的可执行文件,必须用 `&` 调用操作符(本次踩坑点)。
2. 语句分隔符是 `;`,不是 `&&` / `||`(PowerShell 5.x 不支持)。
3. `$` 是变量符号:双引号展开,单引号字面量。
4. 抑制错误流用 `2>$null`。
5. 不支持 `<` 重定向(已弃用),改用 `Get-Content -Raw | & exe.exe`。
6. `;` 顺序执行不等于 `&&` 短路,要短路用 `if ($LASTEXITCODE -eq 0) { ... }`。
7. 常见符号速查表(`&` / `;` / `|` / `>` / `$var` / `@()` / `{}`)。
8. 执行 RunCommand 前的自检清单。

**`user_profile.md`(跨项目记忆)新增 Environment 段**:记录 Windows + PowerShell 环境与常见陷阱摘要,指向各项目 `doc/AGENTS.md` 的详细规则;同时记录宝塔管理 MySQL 的安装目录与端口冲突风险。

### MySQL 切换为本地宝塔管理 + 三服务全部跑通

本机已装宝塔管理的 MySQL 8.0(目录 `E:\software\BtSoft\mysql\MySQL8.0`,版本 8.0.29)占用 3306 端口,导致 Docker MySQL 容器无法启动。经用户确认改用本地 MySQL,Docker 部分只跑 Qdrant + Redis。

**docker-compose.yml 调整**
- 注释掉 mysql 服务定义(保留以备 M6 容器化时切回),volumes 移除 `mysql_data`。
- 顶部加注释说明切换原因和切回 Docker MySQL 的步骤。

**本地 MySQL 初始化**(已执行)
- 新增 `scripts/init-local-mysql.sql`:CREATE DATABASE agent(utf8mb4)+ CREATE USER 'agent'@'localhost'/'%' + GRANT ALL ON agent.*。
- 用 root(密码 123456)执行 init-local-mysql.sql,创建 agent 库 + agent 业务账号。
- 用 agent 账号导入 `src/main/resources/schema.sql`,document + chat_history 两张表建好。

**服务状态验证**
- Qdrant(wibecoding-qdrant):Up,6333/6334 端口。
- Redis(wibecoding-redis):Up,6379 端口(首次拉取 redis:7-alpine 镜像,加速器生效)。
- 本地 MySQL:agent 用户登录成功,document/chat_history 表存在。

**文档同步**
- `doc/ENVIRONMENT.md` 全面更新:
  - §1 服务总览表新增「来源」列,MySQL 标注「本地宝塔管理」,补充说明 1(安装目录 + 切换方式)。
  - §2 快速启动改为「docker compose up -d(只起 Qdrant+Redis)+ MySQL 由宝塔面板管理」。
  - §3.2 MySQL 详情重写:部署方式 / 安装目录 / mysql.exe 路径 / 版本 8.0.29 / 首次初始化命令 / 切回 Docker MySQL 步骤。
  - §5.1 本地开发步骤同步调整。
  - §6 端口速查 3306 备注改为「本地宝塔管理,非 Docker」。
  - §7 凭据速查 root 密码改为「见宝塔面板(本机默认 123456,仅本地用)」。

### 学习文档新增(学习路线 + 原理笔记 + 面试问答)

新增三份面向「学习 + 复习 + 求职」的文档,放在 `doc/` 目录,服务新人入门与面试备战。

**新增 `doc/LEARNING_PATH.md`(学习路线)**
- §0 学习顺序总览:Java 基础 → Spring Boot → MySQL+MyBatis-Plus → Redis → LLM 调用 → RAG → Agent → 工程化 → LangGraph4j,每一步标注"为什么学""学到什么程度""项目里对应哪段代码"。
- §1-§9 分章讲解 9 项技术,每章含「为什么学 / 学到什么程度 / 类比理解 / 项目对应代码 / 学习资源」。
- 入门友好,多用类比(如 Spring Boot 类比餐厅管理体系),少用术语。

**新增 `doc/PRINCIPLES.md`(核心原理笔记)**
- 用类比和 ASCII 图示讲清 8 个核心模块:RAG / Embedding 与向量检索 / Agent Function Calling / SSE 流式输出 / 多轮会话上下文 / LangGraph4j / 配置文件分层 / 统一异常处理。
- 每个原理标注项目代码位置,可"对着代码理解原理"。
- 关键代码片段摘录(如 `@Tool` 注解、`AiServices.builder().tools()` 注册工具、`StreamingResponseHandler.onNext/onComplete`)。

**新增 `doc/INTERVIEW.md`(面试问答手册)**
- 9 大章节 33 道面试题:项目整体 / RAG 模块 / Agent 模块 / 多轮会话 / 流式输出 / Java 后端基础 / 工程化 / 进阶思考 / 面试加分话术。
- 每题含参考答案 + 项目代码位置,面试时能结合代码讲。
- 涵盖高频题(如「为什么用 SSE 不用 WebSocket」「为什么用 Qdrant 不用 Milvus」「RAG 幻觉怎么解决」「数据量大了怎么优化」)和加分话术(讲决策 / 讲数据 / 讲取舍 / 讲思考 / 结合代码)。

**复习建议路径**:LEARNING_PATH.md(入门) → PRINCIPLES.md(理解原理) → INTERVIEW.md(模拟问答)。

## 2026-08-04

### 文档目录整理 + 环境配置文档

把项目文档统一收纳到 `doc/` 目录,新增 `ENVIRONMENT.md` 环境配置说明文档。

**文档迁移**
- 新建 `doc/` 目录,用 `git mv` 把 `AGENTS.md`、`ARCHITECTURE.md`、`CHANGELOG.md` 移入(保留 git 历史)。
- 更新 `doc/AGENTS.md`:必读文档路径改为 `doc/ARCHITECTURE.md` / `doc/CHANGELOG.md`;新增「环境配置」文档登记;新增第 5 条规则(新服务优先 docker-compose 管理,并在 ENVIRONMENT.md 同步登记)。
- 更新 `doc/ARCHITECTURE.md` 第 351 行自引用路径为 `doc/ARCHITECTURE.md`。

**新增 `doc/ENVIRONMENT.md`**
- §1 服务总览:Qdrant / MySQL / Redis(已用)+ App / Elasticsearch(规划)。
- §2 快速启动:docker compose up -d / ps / down / down -v / 单服务启动。
- §3 服务详情:每个服务的镜像、端口、数据卷、凭据、配置键、建表脚本执行方式。
- §4 配置文件分层:application.yml(dev/test/qdrant)用途与环境变量覆盖清单。
- §5 应用启动:本地开发 / 测试 / 生产部署(M6 规划)。
- §6 端口占用速查表。
- §7 凭据速查表(标注仅本地开发用)。
- §8 后续规划:M6(App Dockerfile)/ M7(Elasticsearch 候选,方案未定)/ M8(复用 MySQL 做 LangGraph4j checkpoint,倾向不加新服务)。

**docker-compose.yml 保持现状**:M7 混合检索方案(Elasticsearch / MySQL 全文索引 / Qdrant 稀疏向量)未定,M8 倾向复用 MySQL,故暂不补充新服务,避免过度工程;方案确定后再加。

### M4 Java 后端基本功:MySQL + MyBatis-Plus + Redis 多轮会话完成 ✅

补齐 Java 后端招聘必备的数据库与缓存能力,RAG 对话从单轮升级为多轮会话。

**依赖新增**
- `mysql-connector-j`(runtime):MySQL 8.0 驱动。
- `mybatis-plus-spring-boot3-starter` 3.5.7:ORM + 分页插件 + 逻辑删除。
- `spring-boot-starter-data-redis`:Redis 客户端。
- `h2`(test):测试环境用 H2 内存库替代 MySQL。

**基础设施**
- `docker-compose.yml` 新增 MySQL 8.0(端口 3306,库 agent)与 Redis 7(端口 6379),全栈一键起。
- `application.yml` 新增 MySQL/Redis/MyBatis-Plus 配置;`application-test.yml` 用 H2 + memory session store 替代,测试无需外部服务。
- `schema.sql`:document 与 chat_history 建表脚本,H2 测试自动执行,MySQL 生产手动执行。
- `MybatisPlusConfig`:注册分页插件 `PaginationInnerInterceptor`。

**实体与 Mapper**
- `DocumentEntity`(id/documentId/fileName/chunkCount/fileSize/createdAt/deleted)+ `DocumentMapper`。
- `ChatHistoryEntity`(id/sessionId/role/content/createdAt)+ `ChatHistoryMapper`。
- `AgentApplication` 加 `@MapperScan("com.example.agent.mapper")`。

**多轮会话(Redis / 内存双实现)**
- `SessionStore` 接口(load/append/clear)+ `MemorySessionStore`(测试)+ `RedisSessionStore`(生产,JSON 序列化 + TTL 过期)。
- `ChatSessionProperties` 配置类(store-type/ttl-seconds/max-history),通过 `@ConditionalOnProperty` 切换实现。
- `RagChatServiceImpl` 改造:chat/streamChat 新增 sessionId 参数,加载历史 → 拼 messages → 调 LLM → 保存 user+assistant 到 session store + MySQL。
- 流式对话在 `onComplete` 回调中累积完整回答后保存历史。

**持久化改造**
- `KnowledgeServiceImpl`:上传文档后持久化元数据到 MySQL(document 表)。
- `RagChatServiceImpl`:每轮对话持久化到 MySQL(chat_history 表)。

**新增接口**
- `GET /api/v1/knowledge/documents?page=1&size=10`:分页查询已上传文档。
- `GET /api/v1/chat/history/{sessionId}`:查询某会话的对话历史。
- `ChatRequest` 新增 `sessionId` 字段(可选);`RagChatResponse` 新增 `sessionId` 字段(返回给前端)。

**测试更新**
- `RagChatServiceTest`:适配新构造函数(SessionStore + ChatHistoryMapper),验证多轮会话保存(append 2 次)与历史持久化(insert 2 次)。
- `KnowledgeServiceTest`:适配新构造函数(DocumentMapper),验证文档元数据持久化。
- `AgentApplicationTests`:H2 + MyBatis-Plus + MemorySessionStore 全栈上下文加载通过。
- `mvn test -Dtest=!QdrantVectorStoreTest`:**Tests run: 13, Failures: 0, Errors: 0, Skipped: 0**,全部通过。

**M4 状态**:规划 → 已完成 ✅。文档元数据持久化 + 多轮会话上下文(Redis/内存双实现)+ 对话历史持久化 + 文档分页查询 + 历史查询接口全部打通。

### M3 Agent / Function Calling 修复完成 ✅

修复 `AgentServiceImpl` 的编译错误,补全 M3 最后一环。

**问题**
- `AiServices.builder(Assistant.class).systemMessage(String)` 在 LangChain4j 0.35.0 不存在,导致编译失败。

**修复**
- 改用 `@SystemMessage` 注解注入系统提示:在 `Assistant` 接口的 `chat` 方法上标注 `@SystemMessage("你是一个智能助手...")`,从 builder 链中移除 `.systemMessage(...)` 调用。
- import 调整:新增 `dev.langchain4j.service.SystemMessage`。

**验证**
- `mvn clean compile`:BUILD SUCCESS,编译通过。
- `mvn test -Dtest=!QdrantVectorStoreTest`:**Tests run: 13, Failures: 0, Errors: 0, Skipped: 0**,全部通过。

**M3 状态**:⚠️ 进行中 → ✅ 已完成。Agent / Function Calling 全链路打通,LLM 可自动决策调用 TimeTools / CalculatorTools。

### 引入 LangGraph4j 规划(复杂 Agent 工作流)

在 `ARCHITECTURE.md` 全篇补入 LangGraph4j 1.8.x 的规划,定位为 LangChain4j 之上的「有状态图编排层」,用于处理线性 Agent 无法覆盖的循环/多 Agent/HITL 场景:

- §2.2 规划:AI 能力深化新增「LangGraph4j 复杂工作流:自适应 RAG、多 Agent 协作、状态持久化、Human-in-the-loop」。
- §3 技术选型:新增「Agent 编排 | LangGraph4j 1.8.x(有状态图、循环、多 Agent 协作、HITL) | 规划」。
- §5 包结构:规划扩展新增 `workflow/` 包(LangGraph4j 图编排)。
- §9 里程碑:新增 M8「LangGraph4j 复杂 Agent:自适应 RAG(查询重写循环)+ 多 Agent 协作 + Human-in-the-loop」。
- §10.1 AI 对标:新增「LangGraph4j 有状态图编排(循环/多 Agent/HITL) | 🔲 规划 | 待 M8」。
- §11 简历素材:新增第 ⑧ 条「基于 LangGraph4j 实现自适应 RAG,检索质量不达标时自动重写查询重试,支持人工审批」;面试可深聊点补 3 条(LangChain4j vs LangGraph4j 定位差异、自适应 RAG 循环实现、Checkpoint 断点恢复)。
- §12 待办与风险:补充 LangGraph4j 学习曲线风险与版本兼容风险(M8 需预留学习时间,先做单循环场景再扩展多 Agent)。

选型依据:LangChain4j 解决「能不能用 AI」(LLM 调用/Embedding/Tool Calling),LangGraph4j 解决「怎么编排更复杂的 Agent」(有向状态图/循环/多 Agent/HITL);当前 M2 RAG 与 M3 单轮 Agent 属线性流程,LangChain4j 足够,引入 LangGraph 属过度工程;M8 将做自适应 RAG(检索→评估→重写查询→重试循环)真正需要图编排时再引入。

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
