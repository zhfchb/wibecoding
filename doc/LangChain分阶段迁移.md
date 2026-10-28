# LangChain / LangGraph 分阶段迁移

## Git 管理

各阶段从本地 master 建功能分支，通过测试后提交，再用 --no-ff 合并回 master。
不推送远程；保留各阶段分支与合并记录。阶段五完成后 master 为当前实现。

| 阶段 | 分支 | 实现 |
| --- | --- | --- |
| 1 | codex/phase-1-python-resume | Python 简历解析，临时双实现切换 |
| 2 | codex/phase-2-recruitment-ai | 岗位抽取、匹配语义分、面试题与报告模型调用 |
| 3 | codex/phase-3-rag-agent-stream | RAG、工具、模型原生 token 流、ownerId 检索回调 |
| 4 | codex/phase-4-langgraph-workflows | 自适应查询重写与持久化人工审核图 |
| 5 | codex/phase-5-cleanup-validation | 移除 Java AI 框架、迁移向量化/重排、整体回归 |

第一阶段的 AI_RESUME_PROVIDER 开关已在阶段五删除，当前所有模型调用都使用 Python。
阶段回退请创建 revert 提交或从对应阶段分支建立修复分支，不对已合并历史做 reset。
回退到旧 Java 工作流之前先停止新任务，分别归档两个系统的任务，不能互相解释 checkpoint。

## 服务边界

Java 负责 JWT、岗位与候选人归属、文件提取/切块、MySQL 业务、Redis 会话和 Qdrant/内存向量数据访问。
Python 负责 LangChain 模型调用、结构化结果校验、工具循环、生成、查询重写及 LangGraph 审核。
Java 的规则评分与业务决策保留；业务 prompt 中的数据由已鉴权 Java 构建，Python 负责结构化输出约束。
检索通过固定 JAVA_SERVICE_URL 回调 Java，带共享内部令牌及从 Java 身份解析的 ownerId；无 ownerId 禁止检索。
不复制知识库，用户上传/删除和检索使用原有向量集合和文档元数据。
真实 embedding 由 Python 执行，返回维度必须与 Java/Qdrant 配置一致；更换 embedding 模型需要重新索引，不能混用。

## 内部接口

所有业务接口均验证 X-AI-Service-Token；health 是进程存活检查，不探测模型连接。
Python 不接收终端用户 JWT，外部用户不能自行决定 ownerId。

| Python 路径 | 用途 |
| --- | --- |
| POST /internal/v1/resumes/parse | 简历结构化字段，最大 12000 字符 |
| POST /internal/v1/generate | position/match/interview/report schema 约束生成 |
| POST /internal/v1/chat | 普通模型对话 |
| POST /internal/v1/agent | 时间、计算器、可选 Tavily 工具循环 |
| POST /internal/v1/search | 报告市场行情引用 |
| POST /internal/v1/rag | 带用户范围检索回调、来源与历史的问答 |
| POST /internal/v1/rag/stream | NDJSON sources/token/done/error，Java 转为原 SSE |
| POST /internal/v1/rag/adaptive | LangGraph 检索→重写→重检索→生成/证据不足兜底 |
| POST /internal/v1/embeddings | LangChain OpenAIEmbeddings，批量上限 32 |
| POST /internal/v1/rerank | Python 调用重排服务，Java 校验结果索引及分数 |
| POST /internal/v1/tasks/submit,get,pending,review | 按 ownerId 隔离的持久任务 |

Java POST /internal/v1/retrieve 是唯一内部知识检索回调，单独验证令牌，拒绝缺少归属的请求。
服务失败返回脱敏错误；简历解析失败仍保留 FAILED 标记；rerank 和行情搜索失败按原规则降级。

## 环境变量与启动

Python 3.12。ai-service/requirements.lock 锁定经过测试的依赖，requirements.txt 记录直接依赖范围。

| 配置 | 所属服务 | 用途 |
| --- | --- | --- |
| AI_SERVICE_TOKEN | 两端，必须相同 | 独立随机内部令牌 |
| AI_SERVICE_BASE_URL | Java | Python 地址，独立进程默认 http://localhost:8000 |
| JAVA_SERVICE_URL | Python | 固定 Java 检索回调地址，默认 http://localhost:8080 |
| DEEPSEEK_CLIENT_API_KEY | Python | 模型密钥 |
| DEEPSEEK_BASE_URL / AI_MODEL | Python | 兼容 API，默认 https://api.deepseek.com/v1 / deepseek-chat |
| RAG_EMBEDDING_API_KEY / BASE_URL / MODEL | Python | BGE-M3 或兼容 embedding 服务 |
| RAG_RERANK_API_KEY / BASE_URL / MODEL | Python | 重排，key 可复用 embedding key |
| TAVILY_API_KEY | Python | 可选外部搜索；Java rag.web-search.enabled 控制 RAG/报告使用 |
| AI_CHECKPOINT_DB | Python | 默认 data/workflows.sqlite，必须持久化 |

本地非容器启动步骤见 README；Java、Python 必须各自配置环境，旧 Java 的模型配置不再生效。
本地 Docker：复制 .env.local.example 为 .env.local 并填写，运行 mvn clean package 后：

```powershell
docker compose --env-file .env.local -f docker-compose.local.yml up -d --build
```

生产：复制 .env.prod.example 为 .env.prod 并填写：

```powershell
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

Compose 内部 Python 8000、Java 8080，通过服务名相互调用，Python 不映射宿主端口。
SQLite checkpoint 使用 ai_workflows 命名卷；不要用 down -v 清除待审核任务。
Python 当前限定单进程单 worker/单副本，审核操作在进程内串行化。需要多副本时先迁移到共享数据库 checkpointer 与跨进程并发控制。
旧 MySQL langgraph_checkpoint / multi_agent_task 表不删除，作为迁移前归档；旧 checkpoint 格式不兼容且旧任务缺少 ownerId，不自动暴露或导入新系统。

## 验收与限制

常规 Java/Python 测试与前端构建命令见 README。
RUN_AI_E2E=true + AI_TEST_PYTHON 启用跨服务测试：启动真实 Python 进程与 Java 随机端口，使用本地模拟 OpenAI/embedding/rerank 服务及 H2。
实际验证上传→解析入库→语义匹配→面试题→报告、RAG 来源隔离、SSE 用户归属、Python 进程重启→拒绝重写→审批。
Python 单测另覆盖拒绝上限终止、过期版本冲突、checkpoint 失败节点恢复和查询重写次数上限。
这些测试不证明真实模型质量、真实 MySQL/Qdrant 或生产部署链路。
Docker 构建曾因 Docker Hub 网络超时失败，Compose 配置校验通过；镜像运行仍需在可拉取镜像的环境验证。

参考 LangChain 官方结构化输出 API 与 LangGraph 文档：
https://reference.langchain.com/python/langchain-openai/chat_models/base/ChatOpenAI/with_structured_output
https://docs.langchain.com/oss/python/langgraph/interrupts

最终本地验收：Java 49 项（45 通过、4 环境跳过；含跨服务测试）、Python 26 项通过，JAR 打包与 React 构建成功。
