# 使用文档

> 智能知识库 Agent — 基于 RAG + LangGraph4j 的智能问答系统
> 最后更新：2026-08-06

---

## 一、环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 后端运行环境 |
| Maven | 3.8+ | 后端构建工具 |
| Node.js | 18+ | 前端运行环境（本机路径 `E:\software\nodejs`） |
| MySQL | 8.0+ | 数据存储（用户、文档元数据、多 Agent 任务、checkpoint） |
| Redis | 6.0+ | 会话存储（Docker 容器，可选，测试环境可用内存替代） |
| Qdrant | 1.0+ | 向量数据库（Docker 容器，存储文档 embedding） |

---

## 二、启动依赖服务

### 2.1 MySQL（本机宝塔管理）

```powershell
# 检查 MySQL 是否运行（宝塔默认端口 3306）
& "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uagent -pagent123 -e "SELECT 1"

# 首次需建库建表（已建过可跳过）
& "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uroot -p<root密码> -e "CREATE DATABASE IF NOT EXISTS agent DEFAULT CHARSET utf8mb4;"
& "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uroot -p<root密码> -e "CREATE USER 'agent'@'%' IDENTIFIED BY 'agent123'; GRANT ALL ON agent.* TO 'agent'@'%'; FLUSH PRIVILEGES;"

# 导入表结构
Get-Content src\main\resources\schema.sql -Raw | & "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uagent -pagent123 agent

# 初始化默认用户（admin/admin123, user/user123）
# 参见 scripts/init-local-mysql.sql
```

### 2.2 Redis（Docker）

```powershell
# 启动 Redis 容器（首次）：镜像 redis:7-alpine，端口 6379，持久化数据到 wibecoding_redis_data 卷
docker run -d --name wibecoding-redis `
  -p 6379:6379 `
  -v wibecoding_redis_data:/data `
  --restart unless-stopped `
  redis:7-alpine

# 后续启动（容器已创建过）：
docker start wibecoding-redis

# 检查：
Test-NetConnection -ComputerName localhost -Port 6379
```

### 2.3 Qdrant（Docker）

```powershell
# 启动 Qdrant 容器（首次）：向量数据库，端口 6333/6334，持久化数据到 wibecoding_qdrant_storage 卷
docker run -d --name wibecoding-qdrant `
  -p 6333:6333 -p 6334:6334 `
  -v wibecoding_qdrant_storage:/qdrant/storage `
  --restart unless-stopped `
  qdrant/qdrant:latest

# 后续启动（容器已创建过）：
docker start wibecoding-qdrant

# 检查（端口 6333）：
Test-NetConnection -ComputerName localhost -Port 6333
```

> 两个容器都配置了 `--restart unless-stopped`，Docker 服务启动后会自动拉起，无需手动启动。

---

## 三、启动后端

### 3.1 配置 API Key（必需）

创建 `src/main/resources/application-dev.yml`（已在 `.gitignore`，不会提交）：

```yaml
# 开发环境配置（含真实 API key，不提交 git）
deepseek:
  client:
    api-key: sk-你的DeepSeek密钥        # 对话模型（无此 key 对话功能不可用）

rag:
  embedding:
    api-key: sk-你的硅基流动密钥         # BGE-M3 文本向量化
  rerank:
    enabled: true
    api-key: sk-你的硅基流动密钥         # BGE reranker 精排（可与 embedding 共用）
```

> API key 获取：
> - 硅基流动（Embedding + Rerank）：https://siliconflow.cn
> - DeepSeek（对话模型）：https://platform.deepseek.com

### 3.2 启动命令

```powershell
# 在项目根目录执行
# 必须带 dev profile 才能加载 API key
$env:QDRANT_URL="http://localhost:6333"
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

启动成功标志：
```
Started AgentApplication in X.XXX seconds
Tomcat started on port 8080 (http)
```

### 3.3 验证后端

```powershell
# 健康检查（返回登录页 HTML 说明 8080 已通）
curl http://localhost:8080/api/v1/auth/login -X POST -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'
# 应返回 {"token":"...","tokenType":"Bearer","username":"admin","role":"ADMIN"}
```

---

## 四、启动前端

### 4.1 安装依赖（首次）

```powershell
cd frontend
& "E:\software\nodejs\npm.cmd" install --registry https://registry.npmmirror.com
```

### 4.2 启动开发服务器

```powershell
cd frontend
& "E:\software\nodejs\npm.cmd" run dev
```

启动成功标志：
```
VITE v5.x.x  ready in xxx ms
➜  Local:   http://localhost:5173/
```

前端 dev server 会自动把 `/api` 请求代理到后端 `http://localhost:8080`，无需配置跨域。

### 4.3 访问应用

浏览器打开 `http://localhost:5173/`

---

## 五、默认账号

| 用户名 | 密码 | 角色 | 权限 |
|--------|------|------|------|
| admin | admin123 | ADMIN | 所有功能 + 管理员接口 |
| user | user123 | USER | 所有功能（无管理员接口） |

---

## 六、功能说明

### 6.1 登录 / 注册

- 打开 `http://localhost:5173/` 自动跳转登录页
- 支持登录和注册（注册默认 USER 角色）
- 登录后 JWT 存 localStorage，自动带在后续请求中
- 401 自动跳回登录页

### 6.2 RAG 对话

- 左侧菜单点击「RAG 对话」
- 支持流式 / 非流式切换（右上角开关）
  - **流式模式**：SSE 逐 token 输出，体验更好
  - **非流式模式**：一次性返回完整答案
- 回车发送，Shift+回车换行
- 回答下方显示来源文档标签（文件名 + 切片序号 + 相关性分数）
- 会话 ID 自动生成，支持历史加载

### 6.3 多 Agent 任务

- 左侧菜单点击「多 Agent 任务」
- **工作流**：检索 Agent 找资料 → 生成 Agent 写草稿 → 暂停等待人工审核
- 提交问题后任务状态变为 `AWAITING_REVIEW`
- **审批通过**：草稿转为最终答案，任务完成
- **拒绝并反馈**：填写拒绝原因，生成 Agent 据此重写草稿
- 最多拒绝 2 次（`max-rejects` 配置），超过后强制通过
- **进程重启不丢失**：任务持久化到 MySQL，重启后「待审核任务」列表自动恢复

### 6.4 知识库管理

- 左侧菜单点击「知识库管理」
- **上传文档**：拖拽或点击上传，支持格式：
  - `.txt` — 纯文本
  - `.md` / `.markdown` — Markdown
  - `.pdf` — PDF 文档（基于 Apache PDFBox 解析）
- 单文件最大 10MB
- 上传后自动：切分文本 → Embedding 向量化 → 存入 Qdrant → 元数据存 MySQL
- **PDF 注意事项**：
  - 仅支持带文字层的 PDF（扫描版 PDF 需先 OCR）
  - 表格、图片中的文字不会被提取
- 文档列表支持分页查看

---

## 七、配置说明

### 7.1 配置文件层级

| 文件 | 用途 | 是否提交 git |
|------|------|-------------|
| `application.yml` | 基础配置（端口、数据库、RAG 参数） | ✅ 是 |
| `application-dev.yml` | 开发环境（含 API key） | ❌ 否（gitignore） |
| `application-prod.yml` | 生产环境（环境变量占位） | ✅ 是 |
| `application-test.yml` | 测试环境（H2 内存库） | ✅ 是 |

### 7.2 关键配置项

```yaml
# application.yml 节选
rag:
  retrieval:
    top-k: 5              # 检索召回数量
    min-score: 0.35       # 最低相关性阈值
  hybrid:
    enabled: true         # 混合检索（稠密+稀疏+RRF）
    recall-top-k: 20      # 混合检索召回数
    rrf-k: 60             # RRF 融合参数
  rerank:
    enabled: true         # 精排开关（需 API key）
    top-k: 3              # 精排后保留数量
  upload:
    max-file-size-bytes: 10485760   # 上传限制 10MB
    allowed-extensions: [txt, md, markdown, pdf]
  multi-agent:
    enabled: true         # 多 Agent 工作流开关
    max-rejects: 2        # 最多拒绝次数
```

---

## 八、常见问题

### Q1: 上传文档报「服务器内部错误」

**原因**：Embedding API key 未配置或无效

**解决**：
1. 确认 `application-dev.yml` 中 `rag.embedding.api-key` 已填真实 key
2. 确认后端以 dev profile 启动：`mvn spring-boot:run "-Dspring-boot.run.profiles=dev"`

### Q2: 多 Agent 任务列表报「加载任务列表失败」

**原因**：MySQL 中 `multi_agent_task` 表未创建

**解决**：
```powershell
Get-Content src\main\resources\schema.sql -Raw | & "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uagent -pagent123 agent
```

### Q3: 前端登录后页面空白

**原因**：后端未启动或端口不对

**解决**：
1. 确认后端 8080 端口已启动
2. 确认前端 Vite proxy 配置正确（`vite.config.js` 中 `target: 'http://localhost:8080'`）

### Q4: RAG 对话无响应或报错

**原因**：DeepSeek API key 未配置

**解决**：在 `application-dev.yml` 中配置 `deepseek.client.api-key`

### Q5: PDF 上传后切片数为 0

**原因**：PDF 是扫描版（无文字层）

**解决**：使用带文字的 PDF（如 Word 导出的 PDF），扫描版需先 OCR 处理

### Q6: 端口 8080 被占用

```powershell
# 查找并杀掉占用进程
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

---

## 九、技术栈

### 后端
- Spring Boot 3.3.5 + Java 17
- LangChain4j 1.18.1（Embedding / Chat）
- LangGraph4j 1.8.22（状态图编排）
- MyBatis-Plus 3.5.7 + MySQL 8.0
- Spring Security + JWT（RBAC 鉴权）
- Apache PDFBox 3.0.3（PDF 解析）
- Qdrant（向量数据库）

### 前端
- React 18 + Vite 5
- Ant Design 5（UI 组件库）
- React Router 6（路由）
- Axios（HTTP 请求）
- SSE 流式对话（fetch + ReadableStream）

---

## 十、项目结构

```
wibecoding/
├── src/main/java/com/example/agent/
│   ├── config/           # 配置类（RagProperties, SecurityConfig）
│   ├── controller/       # REST 接口
│   ├── entity/           # 数据库实体
│   ├── mapper/           # MyBatis-Plus Mapper
│   ├── rag/              # RAG 核心（检索、Embedding、向量库、PDF 解析）
│   ├── service/          # 业务逻辑
│   ├── workflow/         # LangGraph4j 工作流
│   └── security/         # JWT 认证
├── src/main/resources/
│   ├── application.yml   # 基础配置
│   ├── application-dev.yml   # 开发配置（gitignore，含 API key）
│   ├── application-prod.yml  # 生产配置
│   └── schema.sql        # 数据库建表脚本
├── frontend/             # React 前端
│   ├── src/
│   │   ├── api/          # API 封装
│   │   ├── context/      # Auth 上下文
│   │   ├── layouts/      # 布局组件
│   │   └── pages/        # 页面（Login/Chat/Agent/Knowledge）
│   └── vite.config.js    # Vite 配置（含 proxy）
└── doc/                  # 文档目录
```
