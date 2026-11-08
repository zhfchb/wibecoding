# Smart Knowledge Agent — 智能招聘助手

Spring Boot 管理招聘业务，Python LangChain / LangGraph 承担 AI 能力。
支持岗位导入、简历解析、规则与语义混合评分、面试题、评估报告、RAG、工具调用、SSE 和人工审核。

公司管理已支持公司资料、成员与部门目录，当前管理员可维护部门，HR只读；招聘数据仍按账号隔离。参见[用户与阶段规划](docs/product/用户定位与角色权限.md)及[部门验收](docs/acceptance/DEPARTMENTS.md)。

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
# deploy/Dockerfile.local 使用上一步的 JAR
docker compose --env-file .env.local -f deploy/docker-compose.local.yml up -d --build
```

Java API：127.0.0.1:18080；Python 仅开放 Compose 内部端口 8000。
前端在 frontend 目录执行 npm ci 与 npm run dev，按需设置 Vite 代理目标为 18080。
默认初始化账号仅供本地测试，使用前检查 scripts/init-users.sql。
已有数据库升级新版本前，先执行 `deploy/migrations/20261107_match_tasks.sql` 创建匹配任务表；已有数据卷不会自动重新初始化。步骤见[匹配任务说明](docs/acceptance/MATCH_TASKS.md)。
失败简历的后台重新解析还需执行 `deploy/migrations/20261107_resume_parse_tasks.sql`，步骤见[简历重解析说明](docs/acceptance/RESUME_PARSE_TASKS.md)。
面试题和评估报告后台生成需执行 `deploy/migrations/20261107_material_tasks.sql`，步骤见[资料生成任务](docs/acceptance/MATERIAL_TASKS.md)。
生产部署使用 deploy/docker-compose.prod.yml 与 .env.prod，详见 docs/development/LangChain分阶段迁移.md。

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

- [公司资料完善](docs/acceptance/COMPANY_PROFILE.md)：省市联动、必填与选填资料；已有库需执行 `deploy/migrations/20261108_company_profile.sql`。

- [公司管理分阶段计划](docs/development/公司管理分阶段计划.md)：第一阶段公司与成员基础已实现，已有数据库需执行 `deploy/migrations/20261108_company_foundation.sql`；部门和协作后续分阶段提交。

- [文档导航](docs/README.md)：产品、开发、验收与历史资料。
- [部署入口](deploy/README.md)：本地、生产与基础服务的启动方式。
- [演示数据](demo-data/README.md)：岗位、简历及导入方式。
- [迁移说明](docs/development/LangChain分阶段迁移.md)：接口、启动、回退和验收。
- [变更日志](docs/变更日志.md)：每阶段改动记录。
- [持久化匹配任务](docs/acceptance/MATCH_TASKS.md)：进度、刷新恢复、幂等、取消、超时和数据库升级。
- [失败简历重新解析](docs/acceptance/RESUME_PARSE_TASKS.md)：逐份/批量重试、部分失败与上传计数修复。
- [面试题与报告后台生成](docs/acceptance/MATERIAL_TASKS.md)：生成进度、恢复、旧内容保护与来源评分。

## 目录

```text
src/          Java 后端源码、配置与测试
ai-service/   Python AI 服务、依赖与测试
frontend/     React 前端
deploy/       Dockerfile、Compose 与数据库初始化
scripts/      数据初始化和维护脚本
docs/         产品、开发、验收与历史文档
demo-data/    岗位和简历演示素材
outputs/      本地日志及截图（不提交）
```

所有启动命令默认从项目根目录执行。原根目录的 Compose 文件已移至 `deploy/`。
本地私有素材位于 `demo-data/local/`，继续被 Git 和 Docker 忽略。
`target/`、`frontend/dist/`、`node_modules/`、`.venv/` 和测试缓存由工具生成，不作为源码维护。

旧设计、面试和历史文档中的 Java AI 框架说明属于迁移前实现，以当前代码和迁移说明为准。
本项目用于学习与演示。
