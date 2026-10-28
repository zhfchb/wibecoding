# Smart Knowledge Agent — 智能招聘助手

Spring Boot 管理招聘业务，Python LangChain / LangGraph 承担 AI 能力。
支持岗位导入、简历解析、规则与语义混合评分、面试题、评估报告、RAG、工具调用、SSE 和人工审核。

## 当前架构

```text
React → Spring Boot（JWT / 权限 / 招聘业务 / 会话历史）→ Python FastAPI
              ↓                                  LangChain / LangGraph
        MySQL / Redis / Qdrant                          ↓
              ↑                              DeepSeek / Embedding / Rerank / Tavily
              └──── 带令牌与 ownerId 的检索回调 ──────────┘
```

- Java：文件解析、切块、知识数据访问、混合召回（稠密 + 稀疏 + RRF）、业务校验与持久化。
- Python：模型调用、Pydantic 结构化输出、Agent 工具循环、RAG 生成与查询重写、持久化人工审核图。
- 真实 embedding 和 rerank 在 Python 执行；hash 向量仅用于本地演示与回归。
- 已移除 LangChain4j / LangGraph4j。原有前端业务接口保留。
- 人工拒绝达到上限后任务失败终止，不能强制变成批准。

## 技术栈

Java 17、Spring Boot 3.3.5、MyBatis-Plus、MySQL 8、Redis、Qdrant；
Python 3.12、FastAPI、LangChain、LangGraph、SQLite checkpoint；React 18、Vite、Ant Design。
Python 版本见 ai-service/requirements.lock，前端版本见 frontend/package-lock.json。

## 本地 Docker 启动

1. 安装 Java 17、Maven、Docker；复制 .env.local.example 为被 Git 忽略的 .env.local 并填入配置。
2. 根目录执行：

```powershell
mvn clean package
# Dockerfile.local 使用上一步的 JAR
docker compose --env-file .env.local -f docker-compose.local.yml up -d --build
```

Java API：127.0.0.1:18080；Python 仅开放 Compose 内部端口 8000。
前端在 frontend 目录执行 npm ci 与 npm run dev，按需设置 Vite 代理目标为 18080。
默认初始化账号仅供本地测试，使用前检查 scripts/init-users.sql。
生产部署使用 docker-compose.prod.yml 与 .env.prod，详见 doc/LangChain分阶段迁移.md。

## 不使用容器启动 Python

在 ai-service 目录执行，确保 python 指向 Python 3.12：

```powershell
python -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r requirements.lock
$env:AI_SERVICE_TOKEN = '替换为独立随机令牌'
$env:DEEPSEEK_CLIENT_API_KEY = '通过本地安全配置提供'
$env:JAVA_SERVICE_URL = 'http://localhost:8080'
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Java 启动终端设置相同 AI_SERVICE_TOKEN 和 AI_SERVICE_BASE_URL=http://localhost:8000。
真实向量化需在 Python 配置 RAG_EMBEDDING_API_KEY，Java 选择 rag.embedding.provider=openai。
本地原 application-dev.yml 中的模型密钥不再由 Java 使用，需迁到 Python 环境变量。

## 测试

```powershell
mvn clean verify
# ai-service 目录
& .\.venv\Scripts\python.exe -m pytest -q
# frontend 目录
npm ci
npm run build
```

跨服务测试（根目录）：

```powershell
$env:RUN_AI_E2E = 'true'
$env:AI_TEST_PYTHON = (Resolve-Path ai-service/.venv/Scripts/python.exe).Path
mvn test '-Dtest=PythonAiE2ETest'
```

该测试启动真实 Java / Python HTTP 服务，使用 H2 和本地模拟模型/向量/重排服务，不消耗真实 API。
涵盖招聘解析/匹配/题目/报告、RAG 来源隔离、SSE 历史隔离、Python 重启后的审核恢复与过期审核冲突；实际 LangChain embedding 和 Python rerank HTTP 路径也参与测试。
真实模型效果、真实 MySQL/Qdrant 部署链路需另行验收，不能用模拟测试结果替代。

## 文档

- doc/LangChain分阶段迁移.md：分阶段 Git、接口、启动、回退和验收。
- doc/项目架构大纲.md：功能规划与迁移后的架构补充。
- doc/环境配置说明.md：服务与环境变量。
- doc/变更日志.md：每阶段改动记录。

旧设计、面试和历史文档中的 Java AI 框架说明属于迁移前实现，以当前代码和迁移说明为准。
本项目用于学习与演示。
