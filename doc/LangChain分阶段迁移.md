# LangChain 分阶段迁移

## 分支与验收

主分支为 `master`。第一阶段从干净的 master 创建 `codex/phase-1-python-resume`。
功能分支内按可审查范围提交，测试通过后再评审、合并；不自动推送远程。
后续阶段从合并后的 master 建分支：匹配/面试题/报告 → RAG/工具/SSE → LangGraph 人工审核 → 清理旧依赖。

## 第一阶段边界

React → Java（鉴权、文件提取、向量入库）→ Python LangChain（结构化抽取）→ Java（候选人入库）。
内部接口 `POST /internal/v1/resumes/parse`，请求 `{"text":"简历文本"}`，最长 12000 字符。
响应字段与原 Java 抽取字段一致；未知年限返回 null。Java 保持原来的单份失败隔离与 FAILED 标记。
Python 不访问数据库，不接收终端用户 JWT；使用独立的 X-AI-Service-Token 鉴权。
失败不自动切回 Java，避免重复模型调用；切换开关可显式回退。
其余模型调用、向量化、工作流仍为 Java，尚未迁移，不删除 LangChain4j。

## 本地启动

在 ai-service 目录运行（PowerShell）：

```powershell
python -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r requirements.lock
$env:AI_SERVICE_TOKEN = '替换为独立随机令牌'
$env:DEEPSEEK_CLIENT_API_KEY = '通过本地安全配置提供真实密钥'
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

启动 Java 的终端设置同一个 AI_SERVICE_TOKEN，并设置 AI_RESUME_PROVIDER=python。
AI_SERVICE_BASE_URL 默认为 http://localhost:8000。
回退时设置 AI_RESUME_PROVIDER=java 并重启 Java。
模型地址 DEEPSEEK_BASE_URL 默认为 https://api.deepseek.com/v1，AI_MODEL 默认为 deepseek-chat。
不要把真实密钥或简历写入提交。health 仅检查进程，不代表模型连通。

## Compose

预先在终端环境提供 AI_SERVICE_TOKEN、DEEPSEEK_CLIENT_API_KEY：

```powershell
docker compose -f docker-compose.local.yml -f docker-compose.ai.yml up -d --build
```

Python 容器仅在 Compose 内网开放 8000，Java 使用 http://ai-service:8000。
可用 AI_MODEL_BASE_URL 指定 Python 模型兼容 API 地址（需包含 /v1）。

## 验证

Python：ai-service 目录执行 `.\.venv\Scripts\python.exe -m pytest -q`。
requirements.txt 声明直接依赖范围，requirements.lock 固定已验证依赖，Docker 与 CI 使用锁定版本。
Java：根目录执行 `mvn test`。
测试使用模拟模型与 HTTP 服务，不等于真实模型、MySQL、Qdrant 的端到端验收。
真实验收：启动两端并登录，上传示例简历，检查 PARSED、字段和持久化；停掉 Python 后上传，检查 FAILED 与错误隔离；回退 java 后复查。

2026-10-28 本地验证：Java 52 项（48 通过、4 环境跳过），Python 10 项全部通过；
包括本地模拟模型 HTTP 下的真实 LangChain 链验证，Compose 合并配置校验通过。
未执行真实模型端到端调用或容器构建，尚未合并 master。

结构化输出采用 LangChain 官方 with_structured_output 的 JSON mode，适配 OpenAI 兼容接口：
https://reference.langchain.com/python/langchain-openai/chat_models/base/ChatOpenAI/with_structured_output
