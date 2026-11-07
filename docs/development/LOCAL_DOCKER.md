> 2026-10-28：本地栈已增加 Python AI 服务，必须配置 AI_SERVICE_TOKEN。当前启动步骤以 README 和 docs/development/LangChain分阶段迁移.md 为准，以下保留原容器初始化说明。

## 向量服务代理修复

本机七七规则模式下，硅基流动的域名代理连接会失败；经 SOCKS 客户端解析目的地址后可以正常完成 TLS。
Python 的向量客户端增加可选配置（不配置时保留原有 HTTPX 环境变量行为）：

```dotenv
VECTOR_PROXY_URL=socks5://host.docker.internal:9675
VECTOR_PROXY_RDNS=false
```

9675 是本机实际监听的 SOCKS 端口，不是 HTTP 端口 9674。其他电脑需按实际端口调整。
`false` 表示由客户端解析目的域名，TLS 仍使用原域名校验证书；没有固定上游 IP，也没有关闭校验。
配置只作用于 embedding / rerank，Java 内部回调和 DeepSeek 继续使用原先路由。
修改后执行：`docker compose --env-file .env.local -f deploy/docker-compose.local.yml up -d --build ai-service`。
本机七七需要保持运行；这是当前代理环境的适配，不代表所有网络均需要代理。
以下较早的验证记录保留用于区分历史失败和本次修复。

本次真实容器验收已通过：BGE-M3 返回 1024 维向量；虚构文档经业务上传接口写入 MySQL/Qdrant；
Java → Python → Java 检索回调 → Qdrant 混合召回 → DeepSeek 回答正确测试口令且带 1 条来源。
测试资料和会话已清理，未上传用户真实简历。
同时修复 Qdrant 查询体的稀疏向量格式、参数化 RRF 的 `query.rrf.k` 位置，
以及将 RRF 排名分误用余弦相似度阈值过滤的问题。
Python 27 项通过；Java 44 项通过、6 项按环境条件跳过；前端构建通过。
新增 Qdrant 混合检索与用户隔离测试仍需设置 `QDRANT_URL` 才运行；上述真实 RAG 验收已另外实测。

## LangChain 迁移后启动验证（2026-10-28 晚）

- 当前代码 `e8ec83b`，Java 已重新打包，Python AI 镜像已构建，五个 Compose 服务已启动。
- Java 测试 44 通过、5 跳过；Python 测试 26 通过；前端生产构建通过。
- Java → Python LangChain → DeepSeek 真实对话成功；Python 真实简历结构化提取成功。
- 前端 http://127.0.0.1:5173，后端 http://127.0.0.1:18080。
- 前端启动：在 frontend 目录执行 `$env:API_PROXY_TARGET='http://127.0.0.1:18080'; npm run dev -- --host 127.0.0.1 --strictPort`。
- 重新启动容器：`docker compose --env-file .env.local -f deploy/docker-compose.local.yml up -d --build`。
- `.env.local` 中增加随机内部令牌、Python 基础镜像地址和容器代理变量，真实值不提交。
- Python 可通过 AI_HTTP_PROXY / AI_HTTPS_PROXY 使用宿主机代理，AI_NO_PROXY 控制直连域名；内部服务始终应直连。
- 本机代理为七七规则模式系统代理，HTTP 端口 9674。硅基流动在宿主机直连成功，在容器直连和代理按域名连接时仍失败。
- 对比诊断：HTTP 代理按域名 CONNECT 失败；curl `--connect-to` 使用本机解析结果、保持原域名 SNI 与证书校验时返回正常 401 未认证响应。说明故障与代理端域名解析或路由有关，尚未完成最终修复。
- 当前不固定硅基流动 IP、不关闭 TLS 校验、不改七七全局模式。容器真实 RAG / embedding 尚未通过。

# 本地容器验证

使用独立 Compose 项目 `wibecoding-local`、独立数据卷和本机端口 18080。
复用已有 mysql:8.0、redis:7-alpine、qdrant/qdrant:latest 镜像。
Java 17 运行时从 Amazon ECR 的 Docker Official Images 公共镜像获取。

## 打包与启动

```powershell
mvn -B -DskipTests package
docker compose -f deploy/docker-compose.local.yml build app
# 无真实密钥：仅启动和基础业务验证，使用 hash embedding。
docker compose -f deploy/docker-compose.local.yml up -d
# 填好 .env.local 后，使用真实 DeepSeek 和硅基流动配置重新创建后端。
docker compose --env-file .env.local -f deploy/docker-compose.local.yml up -d app
```

`.env.local` 已被 Git 忽略，Dockerfile.local 的构建上下文只包含打包后的 JAR。
填写 `DEEPSEEK_CLIENT_API_KEY` 和 `RAG_EMBEDDING_API_KEY`，保留
`RAG_EMBEDDING_PROVIDER=openai`。不要混用 hash 和真实 embedding 创建的数据；
真实 AI 验证前不要上传测试文档到 hash 向量集合。

后端接口文档：http://localhost:18080/swagger-ui.html

初始账号：admin / admin123；user / user123。
这里只构建后端容器，原 Dockerfile 同样不包含 React 前端。

## 本地部署适配

- `deploy/local/01-schema.sql` 来源于主 schema.sql，仅将 `CREATE INDEX IF NOT EXISTS`
  替换为 MySQL 8 支持的 `CREATE INDEX`，供新数据卷首次初始化使用，不要重复执行。
- 健康检查请求公开的 GET `/v3/api-docs`，避免 GET 登录接口返回 405。
- 数据库不映射宿主机端口。仅后端 18080 绑定 127.0.0.1。
- 本地数据库口令和 JWT 配置仅用于本机验证。
- 不带 `--env-file` 时会使用占位模型密钥，无法完成真实问答与简历解析。

## 状态与日志

```powershell
docker compose -f deploy/docker-compose.local.yml ps
docker compose -f deploy/docker-compose.local.yml logs --tail 100 app
docker compose -f deploy/docker-compose.local.yml stop
```

不要使用 `down -v`，除非确定要删除本项目数据卷。

## 本次验证（2026-10-28）

- 拉取 master，提交 `74c8116`。
- Java 17 / Maven 打包成功；现有测试 48 项，44 通过、4 跳过、0 失败。
- 构建 `wibecoding-app:local`，启动后端及 MySQL / Redis / Qdrant。
- MySQL 初始化 11 张表成功，Redis PING 返回 PONG。
- GET `/v3/api-docs`、默认管理员登录、带 JWT 查询 `/api/v1/auth/me`、岗位列表查询均成功。
- 后续已加载 `.env.local`：DeepSeek 真实对话成功；Agent 计算请求返回正确结果 4019（未单独记录工具调用轨迹）。
- 硅基流动 BGE-M3 在宿主机通过 HTTPS 请求实测成功，输出 1024 维向量，密钥有效。
- 容器上传文档在调用 embedding 时出现 TLS 握手终止。临时域名映射未解决，已撤销；容器内 RAG 全链路尚未通过。
- 未验证简历解析和 React 页面。
