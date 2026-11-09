# 择序 · AI 技术招聘协作工作台

[![CI](https://github.com/zhfchb/wibecoding/actions/workflows/ci.yml/badge.svg)](https://github.com/zhfchb/wibecoding/actions/workflows/ci.yml)

面向中小型技术团队的招聘协作项目。Spring Boot 负责业务、权限与持久化，Python FastAPI / LangChain / LangGraph 负责 AI 能力；React 提供公司管理、HR 工作台及独立面试官工作台。AI 提供参考，最终招聘决定由人作出。

**截至 2026-11-09：核心功能已开发完成，已完成单样本真实模型招聘主流程验收，可用于学习、演示及试用。** 生产部署、性能压测和备份恢复演练尚未验收。

2026-11-09一致性修复新增了文档上传租约与向量清理任务：上传完整落库后才返回成功，删除立即禁止检索并在后台重试物理清理，岗位关闭/编辑不再互相覆盖。已有库更新前先执行第13项迁移，见[修复与验收边界](docs/acceptance/CONSISTENCY_FIXES.md)；真实模型记录对应修复前版本，升级后的真实环境复核仍待执行。

## 已实现的功能

| 模块 | 当前能力 |
| --- | --- |
| 公司与部门 | 公司资料、省市联动、必填/选填字段、成员与平级部门管理、部门启停、岗位关联部门 |
| 岗位与简历 | 创建岗位、导入 JD、简历上传与结构化解析、失败简历后台重新解析 |
| 人岗匹配 | 规则与模型语义混合评分、技能命中与缺失、人工初筛、持久化后台任务 |
| 招聘协作 | 按岗位显式授予 VIEWER / EDITOR；实际操作人与数据 owner 分离、撤权校验、操作记录 |
| 面试准备 | 后台生成定制面试题和评估报告，保留已有结果并校验生成来源 |
| 面试官工作台 | 候选人级分配、必要资料读取、私有评价草稿、提交后锁定 |
| 面试排期 | 明确时区、冲突校验、改期/取消、完成/未到场、独立版本及历史 |
| 招聘结果 | 人工记录待定/录用/淘汰/退出，原因、归档、重新开启、版本冲突保护 |
| 进度总览 | 当前岗位结果分布、归档统计、姓名/文件名搜索、组合筛选与待决定提示 |
| 招聘支持 | 私人资料库、带来源的 RAG 问答、SSE、Agent 工具调用、LangGraph 人工审核 |

公司管理员、HR、面试官通过公司角色和岗位授权分工；系统 ADMIN 不绕过公司/岗位权限。VIEWER 只读，EDITOR 可推进招聘流程；岗位修改、上传删除和转授权仍由创建人负责。面试官仅能查看本人获分配候选人的必要资料。

归档仅锁定招聘结果记录，不自动取消任务、撤销面试或删除资料。尚未实现外部通知/日历邀请、候选人投递入口、Offer/入职管理、多公司切换及复杂组织树。详见[产品定位](docs/product/PRODUCT.md)和[角色权限](docs/product/用户定位与角色权限.md)。

## 架构与技术栈

```text
React / Ant Design
        ↓ JWT
Spring Boot：公司、权限、招聘、任务、会话与数据访问
        ├── MySQL / Redis / Qdrant
        └── Python FastAPI / LangChain / LangGraph
                    ├── DeepSeek：生成、解析、语义评分
                    ├── 硅基流动：Embedding / 可选 Rerank
                    └── SQLite checkpoint：人工审核工作流
        ↑ 带内部令牌及 ownerId 的检索回调
```

- Java 17、Spring Boot 3.3.5、MyBatis-Plus、MySQL 8、Redis、Qdrant。
- Python 3.12、FastAPI、LangChain、LangGraph；依赖锁定于 [requirements.lock](ai-service/requirements.lock)。
- React 18、Vite 5、Ant Design；依赖锁定于 [package-lock.json](frontend/package-lock.json)。
- Java 承担文档解析/切块、混合召回（稠密 + 稀疏 + RRF）及业务校验；Python 执行真实模型、向量与重排请求。
- 已移除 LangChain4j / LangGraph4j。hash 向量用于演示和自动回归，与真实向量数据应分开使用。

## 本地启动（Windows PowerShell）

准备 Java 17、Maven、Docker Compose、Python 3.12 和 Node.js（CI 使用 Node 20）。所有命令从项目根目录开始。

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
| `TAVILY_API_KEY` | 可选市场搜索配置；未配置时不能据此宣称真实市场引用已验证 |

代理仅在实际网络需要时配置，见[本地 Docker 说明](docs/development/LOCAL_DOCKER.md)。真实密钥、测试账号、备份不提交 Git。

### 2. 启动后端

```powershell
mvn clean package
#deploy/Dockerfile.local 使用 target 中的 JAR
# 已有数据库先按下方“数据库升级”完成迁移，再启动应用
docker compose --env-file .env.local -fdeploy/docker-compose.local.yml up -d --build
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
docker compose --env-file .env.local -fdeploy/docker-compose.local.yml stop
```

## 数据库升级

新数据卷由 `deploy/local/01-schema.sql` 初始化；已有数据卷不会自动重跑初始化脚本。先备份，再按[数据库迁移顺序](deploy/migrations/README.md)执行对应增量 SQL，完成后更新前后端。

迁移覆盖后台任务、公司资料、部门、岗位协作、面试分配/排期和招聘结果。旧岗位不自动猜测部门或迁移归属；`review_position_departments.sql` 仅供人工核对。详细部署入口见 [deploy/README.md](deploy/README.md)。生产 Compose 是配置模板，不代表已完成生产部署验收。

## 使用流程

1. 公司管理员完善公司资料，维护部门和 HR / 面试官成员。
2. HR 创建岗位或导入 JD，上传简历并检查解析结果。
3. 执行匹配、核对评分依据，人工决定初筛结果；可授权指定成员协作。
4. 生成面试题和报告，分配面试官并安排时间。
5. 面试官在独立工作台准备面试、保存草稿，面试结束后提交评价。
6. HR 结合证据记录最终招聘结果、填写原因并归档；从进度总览跟进待决定人选。

详见[使用文档](docs/development/使用文档.md)。

## 验证结果与运行方式

2026-11-08 的完整自动化回归为 **185 项：180 通过、5 跳过**，前端构建及进度、结果、招聘协作三类页面回归通过。日期和范围对应已有验收记录，不表示每次克隆自动运行了这些检查。

2026-11-09一致性修复后，后端全量增至**200项：195通过、5跳过**，前端构建通过；本轮未重复浏览器或真实模型验证，详见[修复验收](docs/acceptance/CONSISTENCY_FIXES.md)。

统一本地验收（复用已有 Python 虚拟环境及 Playwright/Chrome，前端 Vite 需已启动）：

```powershell
python scripts/verify-release.py
# 不运行浏览器时
python scripts/verify-release.py --skip-browser
```

报告保存到被忽略的 `outputs/acceptance/`。自动验收使用 H2、本地向量与模拟 AI；另有真实 HTTP/JWT/multipart 跨模块流程和并发验证，见[统一验收说明](docs/acceptance/RELEASE_VERIFICATION.md)。

首次准备 Python 环境：

```powershell
py -3.12 -m venv ai-service/.venv
& .\ai-service\.venv\Scripts\python.exe -m pip install -r ai-service/requirements.lock
& .\ai-service\.venv\Scripts\python.exe -m pytest -q ai-service
```

真实部署已使用 DeepSeek 与硅基流动完成单份虚构简历的解析、1024 维 Qdrant 写入、无降级匹配、6 道面试题、报告、带来源 RAG、排期、人工评价、录用与归档/重新开启，并通过四角色真实页面检查。该样本报告没有外部市场引用；未进行压力测试或生产发布。详情见[真实招聘验收](docs/acceptance/LIVE_RECRUITMENT_ACCEPTANCE.md)。

如需自行执行真实验收，以下命令会创建独立虚构测试数据并消耗真实模型额度：

```powershell
python scripts/verify-live-recruitment.py
# 使用首次输出的 state.json 路径做四角色页面验证
python scripts/verify-live-ui.py --state outputs/acceptance/live-时间戳/state.json
```

真实脚本支持恢复已有状态，不应为重试而反复新建付费流程。压测、备份恢复演练和生产部署验收仍待执行。

## 目录与文档

```text
src/          Java 业务、权限、配置及测试
ai-service/   Python AI 服务及锁定依赖
frontend/     React 工作台
deploy/      Compose、Dockerfile、初始化与增量迁移
scripts/      数据初始化、自动回归、真实验收入口
docs/         产品、开发、验收与历史资料
demo-data/    公开演示素材；local/ 私有素材不提交
outputs/      本地日志、截图、测试账号与报告，不提交
```

- [文档导航](docs/README.md)：统一入口。
- [产品定位与权限](docs/product/PRODUCT.md)、[公司管理分阶段记录](docs/development/公司管理分阶段计划.md)。
- [部署说明](deploy/README.md)、[数据库迁移](deploy/migrations/README.md)、[环境配置](docs/development/环境配置说明.md)。
- [架构大纲](docs/development/项目架构大纲.md)、[AI 迁移记录](docs/development/LangChain分阶段迁移.md)、[变更日志](docs/变更日志.md)。

`target/`、`.venv/`、`node_modules/`、`frontend/dist/` 为生成目录。旧设计和面试资料按历史背景阅读，当前行为以代码及上述验收记录为准。本项目用于学习、演示和试用。
