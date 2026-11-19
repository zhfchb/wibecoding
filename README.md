# 择序 · AI 技术招聘协作工作台

[![CI](https://github.com/zhfchb/wibecoding/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/zhfchb/wibecoding/actions/workflows/ci.yml)

择序是一套面向技术招聘团队的协作工作台。HR 可以从岗位出发管理简历、核对匹配依据、安排面试并记录招聘结果。面试官在独立工作台查看获分配的候选人、准备面试并提交评价。AI 辅助整理信息，招聘团队负责最终决策。

[快速启动](#本地启动windows-powershell) · [招聘流程指南](docs/product/招聘流程操作指南.md) · [源码导读](docs/development/项目结构与源码阅读路线.md) · [文档中心](docs/README.md)

## 核心功能

| 场景 | 功能 |
| --- | --- |
| 岗位与初筛 | 创建岗位或导入 JD，上传简历并解析，查看候选人与岗位要求的匹配依据。 |
| 面试协作 | 生成面试题和参考报告，按轮次分配面试官、安排时间并收集评价。 |
| 招聘跟进 | 查看岗位进度、记录候选人沟通与招聘结果，支持归档和重新开启。 |
| 资料问答 | 批量上传招聘资料，查看带原文引用的回答，管理会话及审核生成草稿。 |
| 组织与权限 | 管理公司资料、部门与成员，通过岗位授权控制协作者的访问范围。 |

## 技术架构

| 层 | 技术与职责 |
| --- | --- |
| Web 工作台 | React、Ant Design、Vite，提供招聘、面试及公司管理界面。 |
| 业务服务 | Java 17、Spring Boot、MyBatis-Plus，处理权限、招聘流程及后台任务。 |
| AI 服务 | Python、FastAPI、LangChain、LangGraph，执行模型调用及人工审核工作流。 |
| 数据存储 | MySQL 保存业务记录，Redis 支持会话存储，Qdrant 保存向量，SQLite 持久化审核检查点。 |

本地配置支持 DeepSeek 和硅基流动。Java 服务负责资料切分与检索，Python 服务负责模型调用、向量化和可选重排。

## 本地启动（Windows PowerShell）

准备 Java 17、Maven、Docker Compose和 Node.js（CI 使用 Node 20）。所有命令从项目根目录开始。

### 1. 配置模型

首次使用复制模板，已有 `.env.local` 时保留现有文件：

```powershell
Copy-Item .env.local.example .env.local
```

编辑 `.env.local`：

| 配置项 | 用途 |
| --- | --- |
| `AI_SERVICE_TOKEN` | 自行生成的长随机内部令牌，Java/Python 共用 |
| `DEEPSEEK_CLIENT_API_KEY` | DeepSeek 密钥 |
| `AI_MODEL_BASE_URL` / `AI_MODEL` | 默认 `https://api.deepseek.com/v1` / `deepseek-chat` |
| `RAG_EMBEDDING_PROVIDER` | 真实向量化设为 `openai`；模板默认为 `hash` |
| `RAG_EMBEDDING_API_KEY` | 硅基流动密钥 |
| `RAG_EMBEDDING_BASE_URL` / `RAG_EMBEDDING_MODEL` | 默认硅基流动地址 / `BAAI/bge-m3` |
| `TAVILY_API_KEY` | 可选市场搜索配置 |

代理仅在实际网络需要时配置，见[本地 Docker 说明](docs/development/LOCAL_DOCKER.md)。真实密钥、测试账号、备份不提交 Git。

### 2. 启动后端

```powershell
mvn clean package
# 已有数据库先按迁移指南完成迁移，再启动应用
docker compose --env-file .env.local -f deploy/docker-compose.local.yml up -d --build
```

本地栈包含 Java、Python、MySQL、Redis、Qdrant，项目名为 `wibecoding-local`。Java API 为 [http://127.0.0.1:18080](http://127.0.0.1:18080)，接口文档为 [Swagger UI](http://127.0.0.1:18080/swagger-ui.html)；Python 8000 仅在 Compose 内部访问。初始账号配置见 [init-users.sql](scripts/init-users.sql)，仅供本地测试。

### 3. 启动前端

在另一个终端执行：

```powershell
Set-Location frontend
npm ci
$env:API_PROXY_TARGET = 'http://127.0.0.1:18080'
npm run dev -- --host 127.0.0.1 --strictPort
```

访问 [http://127.0.0.1:5173](http://127.0.0.1:5173)。代理目标须与 Docker 后端端口一致。

停止服务但保留数据：

```powershell
docker compose --env-file .env.local -f deploy/docker-compose.local.yml stop
```

## 文档

| 主题 | 入口 |
| --- | --- |
| 使用与演示 | [招聘流程指南](docs/product/招聘流程操作指南.md) · [资料库练习](docs/development/招聘资料库学习练习.md) · [面试工作台练习](docs/development/面试工作台练习.md) |
| 部署与升级 | [部署说明](deploy/README.md) · [本地 Docker 配置](docs/development/LOCAL_DOCKER.md) · [数据库迁移](deploy/migrations/README.md) |
| 开发与验证 | [源码导读](docs/development/项目结构与源码阅读路线.md) · [验收指南](docs/acceptance/RELEASE_VERIFICATION.md) · [脚本导航](scripts/README.md) |
| 版本记录 | [变更日志](docs/变更日志.md) · [v1.0.0 验收记录](docs/acceptance/PROJECT_STRUCTURE_20261116.md) |

升级已有数据库时，请先备份，再按迁移顺序执行增量 SQL。项目当前面向学习、演示与本地试用。
