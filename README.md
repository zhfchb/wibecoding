# 🧠 Smart Knowledge Agent

基于 RAG（检索增强生成）+ Agent 工具调用的智能知识库系统。上传文档 → 语义检索 → LLM 生成回答，支持 Web 搜索兜底、多 Agent 协作、自适应 RAG 重写等进阶能力。

## ✨ Features

- 📚 **知识库管理**：上传 TXT / MD / PDF 文档，自动切块、向量化、持久化
- 🔍 **混合检索**：稠密（语义向量）+ 稀疏（关键词 BM25 风格）双路召回，RRF 融合排序
- 🎯 **Rerank 精排**：硅基流动 BGE-Reranker-v2-m3 对召回候选做二次排序
- 🌐 **Web 搜索兜底**：知识库无结果时自动调用 Tavily 搜索实时信息（天气、新闻、行情…）
- 🤖 **Agent 工具调用**：通过 LangChain4j `@Tool` 注解暴露计算器、时间、搜索等工具，LLM 自主决定调用时机
- 🔄 **自适应 RAG**：LangGraph4j 状态图实现"查询重写→检索→评分→重试/生成"链路
- 👥 **多 Agent 协作**：检索 Agent → 生成 Agent → HITL 人工审核的工作流
- 💬 **多轮对话**：基于 MySQL 持久化的会话历史，支持流式 SSE 输出
- 🔐 **JWT 认证 + RBAC**：admin / user 两种角色，Spring Security 安全过滤链
- 🐳 **一键部署**：Docker Compose 全栈部署（app + MySQL + Redis + Qdrant）

## 🏗️ Architecture

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   React 18   │────▶│  Spring Boot │────▶│   MySQL 8    │
│  (Vite+AntD) │◀────│   3.3.5      │◀────│  数据持久化   │
└──────────────┘    └──────┬───────┘    └──────────────┘
                           │
           ┌──────────────┼──────────────┐
           ▼              ▼              ▼
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │    Redis    │ │   Qdrant    │ │  DeepSeek   │
    │  会话缓存    │ │  向量数据库  │ │  LLM / 聊天  │
    └─────────────┘ └─────────────┘ └─────────────┘
           │              │              │
           │              │              ▼
           │              │       ┌─────────────┐
           │              │       │ 硅基流动 API  │
           │              │       │ Embedding/   │
           │              │       │   Rerank    │
           │              │       └─────────────┘
           │              │
           ▼              ▼
    ┌─────────────────────────────────┐
    │   LangChain4j + LangGraph4j     │
    │   Agent / Tools / RAG Graph    │
    └─────────────────────────────────┘
```

## 🛠️ Tech Stack

| 分层 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.3.5 |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis | 7 |
| 向量库 | Qdrant / InMemoryVectorStore | — |
| LLM 框架 | LangChain4j | 1.18.1 |
| Agent 工作流 | LangGraph4j | 1.8.22 |
| LLM | DeepSeek | V3/V4 |
| Embedding | SiliconFlow BGE-M3 | 1024-dim |
| Rerank | SiliconFlow BGE-Reranker-v2-m3 | — |
| 认证 | Spring Security + JWT (jjwt 0.12.6) | — |
| ORM | MyBatis-Plus | 3.5.7 |
| API 文档 | SpringDoc OpenAPI (Swagger) | 2.6.0 |
| 前端 | React + Vite + Ant Design | 18 / 5 / 5 |
| Markdown 渲染 | react-markdown + remark-gfm | — |
| PDF 解析 | PDFBox | — |
| 部署 | Docker + Docker Compose | — |

## 📁 Project Structure

```
wibecoding/
├── src/main/java/com/example/agent/
│   ├── config/           # 配置类（RagProperties、JwtProperties 等）
│   ├── controller/       # REST API 控制器
│   ├── domain/           # 请求/响应 DTO
│   ├── entity/           # 数据库实体
│   ├── mapper/           # MyBatis-Plus Mapper
│   ├── rag/              # RAG 核心（向量化、检索、重排、自适应图）
│   ├── security/         # Spring Security + JWT
│   ├── service/          # 业务层（Agent、Chat、Knowledge、MultiAgent）
│   ├── tools/            # Agent 工具（@Tool 注解）
│   └── workflow/         # 多 Agent 工作流
├── src/main/resources/
│   ├── application.yml   # 主配置
│   └── schema.sql        # 建表 DDL
├── frontend/             # React 前端
├── scripts/              # 初始化脚本
├── doc/                  # 设计文档
├── Dockerfile
├── docker-compose.yml    # 开发环境（Qdrant + Redis）
└── docker-compose.prod.yml  # 生产环境全栈部署
```

## 🚀 Quick Start

### Prerequisites

- JDK 17+
- Node.js 18+
- MySQL 8.0+
- Redis 7+
- Maven 3.9+

### 1. Clone & Setup Database

```bash
# Clone
git clone <repo-url> && cd wibecoding

# 启动基础设施（Qdrant 向量库 + Redis）
docker compose up -d qdrant redis

# 初始化 MySQL 数据库和用户
mysql -u root -p < scripts/init-local-mysql.sql
mysql -u agent -pagent123 agent < scripts/init-users.sql
```

### 2. Configure API Keys

```bash
# 复制开发环境配置
cp src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml
```

编辑 `application-dev.yml`，填入真实 API Key：

```yaml
deepseek:
  client:
    api-key: "sk-your-deepseek-key"

rag:
  embedding:
    api-key: "sk-your-siliconflow-key"
  web-search:
    enabled: true
    provider: tavily
    api-key: "tvly-your-tavily-key"
```

### 3. Start Backend

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

后端启动后访问：
- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

### 4. Start Frontend

```bash
cd frontend
npm install
npm run dev
```

前端访问：http://localhost:5173

### 5. Login

默认账号：
| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | ADMIN |
| user | user123 | USER |

## 📖 API Overview

| Method | Endpoint | Description |
|--------|----------|-------------|
| **Auth** | | |
| POST | `/api/v1/auth/login` | 登录获取 JWT |
| POST | `/api/v1/auth/register` | 用户注册 |
| **RAG Chat** | | |
| POST | `/api/v1/rag/chat` | 同步问答 |
| GET | `/api/v1/rag/chat/stream` | SSE 流式问答 |
| POST | `/api/v1/rag/adaptive` | 自适应 RAG 问答 |
| **Knowledge** | | |
| POST | `/api/v1/knowledge/upload` | 上传文档（TXT/MD/PDF，≤50MB） |
| GET | `/api/v1/knowledge/list` | 文档列表 |
| DELETE | `/api/v1/knowledge/{id}` | 删除文档（含向量数据） |
| **Agent** | | |
| POST | `/api/v1/agent/chat` | Agent 自由对话（工具调用） |
| POST | `/api/v1/agent/multi-agent` | 多 Agent 协作任务 |
| **Session** | | |
| GET | `/api/v1/chat/sessions` | 会话列表 |
| DELETE | `/api/v1/chat/sessions/{id}` | 删除单个会话 |

## 🧩 RAG Pipeline

```
用户提问
  │
  ▼
┌──────────────┐
│  自适应 RAG   │  LangGraph4j 状态图
│  (可选启用)   │  查询重写 → 检索 → 评分 → 重试/生成
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   混合检索    │  稠密向量(BGE-M3) + 稀疏关键词
│  Hybrid Ret.  │  RRF 融合排序
└──────┬───────┘
       │
       ▼
┌──────────────┐
│  Rerank 精排  │  BGE-Reranker-v2-m3
│  (可选启用)   │
└──────┬───────┘
       │
       ▼
┌──────────────┐     无结果 / 低相关
│  LLM 生成回答 │──────────────────▶ Agent + Tavily 搜索兜底
└──────────────┘
```

## 🌐 Web Search Fallback

当知识库检索结果不足（top1 分数 < `fallback-min-score`，默认 0.35）且问题不属于闲聊时，系统自动：

1. 将问题路由到 Agent 服务
2. Agent 通过 Function Calling 调用 `WebSearchTools.search()`
3. Tavily API 返回搜索结果（默认 5 条）
4. LLM 基于搜索结果整合生成最终回答

**配置**（`application.yml`）：

```yaml
rag:
  web-search:
    enabled: false           # 生产环境按需开启
    provider: tavily
    api-key: ""              # Tavily API Key
    max-results: 5           # 每次搜索返回条数
    fallback-min-score: 0.35 # 检索低于此分数触发兜底；0=仅在完全空时触发
```

## 🤖 Agent Tools

通过 LangChain4j `@Tool` 注解声明，LLM 根据用户意图自动选择调用：

| 工具 | 类 | 功能 |
|------|----|------|
| 计算器 | `CalculatorTools` | 数学表达式计算 |
| 时间查询 | `TimeTools` | 当前日期时间 |
| Web 搜索 | `WebSearchTools` | Tavily 互联网搜索 |

## 🐳 Production Deployment

### 全栈 Docker Compose

```bash
# 复制环境变量模板
cp .env.prod.example .env.prod
# 编辑 .env.prod 填入真实密钥

# 一键启动（MySQL + Redis + Qdrant + App）
docker compose -f docker-compose.prod.yml up -d --build
```

或手动构建镜像：

```bash
docker build -t wibecoding-app:latest .
docker run -d -p 8080:8080 --env-file .env.prod wibecoding-app:latest
```

### 环境变量说明

| 变量 | 说明 |
|------|------|
| `DEEPSEEK_CLIENT_API_KEY` | DeepSeek API Key |
| `RAG_EMBEDDING_API_KEY` | 硅基流动 API Key（Embedding/Rerank 共用） |
| `JWT_SECRET` | JWT 签名密钥（≥32 字符） |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 |
| `MYSQL_PASSWORD` | MySQL agent 账号密码 |
| `QDRANT_URL` | Qdrant 地址 |

## 🧪 Testing

```bash
# 运行全部测试
mvn test

# 测试涵盖：文档切块、向量存储、稀疏嵌入、RAG 服务、知识服务、多 Agent 工作流
```

## 📄 License

This project is for educational purposes.
