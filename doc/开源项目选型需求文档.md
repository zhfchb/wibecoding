# AI Agent 开源项目选型需求文档

> 用途：发给 GitHub Copilot / ChatGPT / Claude 等 AI 助手，让其基于以下要求在 GitHub 上**检索、比对、排序并推荐合适的开源项目**。
> 生成日期：2026-08-10

---

## 一、目标

寻找 **2025-2026 年更新维护、技术栈与当前 AI Agent 岗位主流需求高度对齐** 的生产级 / 高星开源项目，用于**学习参考、代码实战、简历项目**。核心关注点：**LangChain / LangGraph、RAG、MCP、多 Agent 协同、Tool Calling、可观测性**。

---

## 二、硬性要求（Must Have）

### 2.1 项目活跃性
- 【必须】**最近 6 个月内有 commit 更新**（`pushed_at` ≥ 2026-02-01）
- 【必须】**GitHub Star ≥ 1k**（或同等社区热度，如 weekly download 数高）
- 【必须】有完整的 `README.md`，含**安装 / 快速启动 / 架构图**说明
- 【必须】至少有 3 位以上 contributor；不接受个人单仓库长期不更新的"死项目"
- 【加分】近期有 release tag，或处于 v1.0+ / GA 稳定阶段

### 2.2 技术栈硬性匹配（与 JD 高频词对齐）
- 【必须】**Python 3.10+** 为主语言
- 【必须】使用以下 Agent 框架中的 **至少 2 个**：
  - LangChain
  - **LangGraph（强偏好，有状态图 / checkpoint 持久化）**
  - LlamaIndex
  - CrewAI
  - AutoGen
  - OpenAI Agents SDK
- 【必须】**具备完整 RAG 流程**（文档解析 → 分块 → Embedding → 向量存储 → 检索 → 重排 → 上下文拼接 → 生成）
- 【必须】实现 **Tool / Function Calling**（至少 3 个以上真实工具，如搜索、代码执行、数据库查询）
- 【必须】至少支持 2 个及以上向量数据库（Milvus / Qdrant / Pinecone / FAISS / Chroma / pgvector 任选 ≥2）
- 【必须】有配置化支持多 LLM 后端（OpenAI GPT / Claude / 国产大模型 DeepSeek/Qwen 等）
- 【必须】项目有 `requirements.txt` / `pyproject.toml`，可通过 pip 一键安装
- 【必须】项目支持 Docker Compose 一键启动（或等价的一键运行脚本）

---

## 三、软性加分项（Nice to Have — 按权重降序）

### 3.1 🔴 高权重加分
| 加分项 | 说明 | 关联岗位需求频次 |
|--------|------|----------------|
| **MCP (Model Context Protocol)** | 官方或第三方 MCP server/client 集成；支持外部工具通过 MCP 注册 | 9/12 |
| **多 Agent 工作流** | 2 个以上 Agent 协同（检索 Agent / 生成 Agent / 审阅 Agent），含 HITL 人工审核 | 6/12 |
| **Checkpoint 持久化** | 对话 / Agent 执行流程断点可恢复（LangGraph Checkpoint / 自定义持久化） | 强相关 |
| **生产级可观测性** | LangSmith / Langfuse / OpenTelemetry / Prometheus 任一种追踪指标 | LLMOps 常见 |
| **混合检索 + Rerank** | 稀疏+稠密双路召回；BGE-Reranker / Cohere Rerank | RAG 进阶 |
| **流式输出** | 支持 SSE / WebSocket 流式打字机效果 | 主流标配 |

### 3.2 🟠 中权重加分
- **Guardrails / 护栏**：Prompt 注入防御、输出结构化校验、Rejection sampling
- **Evals 评估体系**：内置自动化评测（RAGAS、TrueLlama、自定义指标）
- **记忆系统（Memory）**：短期 / 长期 / 会话记忆，或向量记忆
- **vLLM / SGLang 推理**：支持高吞吐推理后端
- **微调（LoRA / SFT）示例**：包含 domain fine-tuning 脚本或文档
- **FastAPI / REST API**：提供清晰的 HTTP 接口
- **微服务 + Docker Compose 全栈部署**：App + VectorDB + Redis + LLM Gateway 一键起

### 3.3 🟢 一般加分
- **前端界面**（Next.js / React / Gradio / Streamlit）
- **知识图谱集成**（Neo4j / NebulaGraph）
- **TS/JS 版同构 SDK**（如 MCP client）
- **CI/CD 流水线**（GitHub Actions）+ 测试覆盖率 ≥ 60%
- **中文文档 / 中文社区支持**
- **License 友好**：MIT / Apache-2.0，避免 AGPL

---

## 四、排除项（Exclude）

- ❌ **仅 Demo / 脚手架**：代码只有一个 notebook 或单文件示例，无工程结构
- ❌ **纯 Prompt 库**：只提供 prompt template，无真实 RAG / Agent 运行时
- ❌ **闭源核心**：核心 Agent 推理走付费 SaaS，本地不可复现
- ❌ **依赖某厂商专有 SDK 才能运行**（如仅绑定某闭源云平台 API Gateway）
- ❌ **仅支持单一向量库 + 单一 LLM**，且无抽象层
- ❌ **GPL / AGPL 强传染性协议**
- ❌ **纯中文 toy project**：代码结构不规范、无测试、无英文文档（中英文双语文档除外）

---

## 五、期望的推荐输出格式

请在 GitHub 上搜索并筛选符合以上条件的项目后，按如下结构输出：

### 推荐结果（按综合匹配度从高到低）

#### 🥇 第 1 名：项目名（Repo URL）
- **Stars / 最近更新：** xxx stars，最后 commit：YYYY-MM-DD
- **License：** xxx
- **✅ 满足的硬性要求：** 逐一列出
- **🌟 关键加分项：** 列出最强的 3-5 个
- **⚠️ 不足 / 坑：** 缺失的能力或已知 issue
- **技术栈覆盖雷达：** （每项 √/×）
  - LangChain / LangGraph / LlamaIndex / CrewAI / AutoGen：
  - RAG + 混合检索 + Rerank：
  - Tool / Function Calling：
  - MCP 支持：
  - 多 Agent 工作流：
  - Checkpoint 持久化：
  - 可观测性（LangSmith 等）：
  - 流式输出：
  - 多 LLM 支持：
  - 多向量库支持：
  - Docker Compose：
- **一句话推荐理由：**

#### 🥈 第 2 名 / 🥉 第 3 名 / ...

> 至少推荐 **5-8 个项目**，按质量分层（顶级生产级 / 次一级学习向 / 垂直场景特色）。

### 汇总对比表

| 项目 | Stars | 更新时间 | License | LangGraph | RAG+Rerank | Tool Calling | MCP | 多Agent | Checkpoint | 可观测 | 流式 | Docker Compose |
|------|-------|---------|---------|-----------|------------|--------------|-----|---------|------------|--------|------|----------------|
| ...  |       |         |         |           |            |              |     |         |            |        |      |                |

### 最终购买 / 采纳建议（Top Pick）
从工程成熟度、活跃度、技术栈覆盖度三个维度综合评估，挑一个 **最适合作为学习样板** 的项目，给出理由和上手路径（启动步骤概述）。

---

## 六、给 Copilot / 检索助手的检索提示词（Keywords Hint）

Copilot 在 GitHub 上搜索时可优先使用以下关键词组合：

```
langgraph agent rag mcp multi-agent tool-calling production
langchain checkpoint persistence vectorstore qdrant milvus rerank
agentic ai framework "model context protocol" mcp-server
llm agent orchestration guardrails observability docker compose
ai agent engineer tech stack 2026 stars:>=1000 pushed:>=2026-02-01
```

---

## 七、附录：参考的岗位 JD 技术栈频次（2026-08 采样）

> Copilot 可参考该频次进行加权匹配。

| 频次档 | 关键词 |
|--------|--------|
| 12次（必需） | Python, RAG |
| 10-11次 | LangChain, LangGraph |
| 8-9次 | MCP, Tool Calling, Prompt Engineering |
| 6-7次 | OpenAI GPT, LlamaIndex, Milvus, Docker, K8s, 多Agent协同 |
| 5次 | Java, AutoGen, CrewAI, Chroma, FAISS, Claude, Function Calling, 微调 SFT/LoRA, PyTorch |
| 3-4次 | TypeScript, Qdrant, Embedding, 国产大模型, Gemini, vLLM, Memory, Pinecone, Weaviate, CI/CD, FastAPI, Hugging Face |
