> 2026-10-28：本地栈已增加 Python AI 服务，必须配置 AI_SERVICE_TOKEN。当前启动步骤以 README 和 doc/LangChain分阶段迁移.md 为准，以下保留原容器初始化说明。

# 本地容器验证

使用独立 Compose 项目 `wibecoding-local`、独立数据卷和本机端口 18080。
复用已有 mysql:8.0、redis:7-alpine、qdrant/qdrant:latest 镜像。
Java 17 运行时从 Amazon ECR 的 Docker Official Images 公共镜像获取。

## 打包与启动

```powershell
mvn -B -DskipTests package
docker compose -f docker-compose.local.yml build app
# 无真实密钥：仅启动和基础业务验证，使用 hash embedding。
docker compose -f docker-compose.local.yml up -d
# 填好 .env.local 后，使用真实 DeepSeek 和硅基流动配置重新创建后端。
docker compose --env-file .env.local -f docker-compose.local.yml up -d app
```

`.env.local` 已被 Git 忽略，Dockerfile.local 的构建上下文只包含打包后的 JAR。
填写 `DEEPSEEK_CLIENT_API_KEY` 和 `RAG_EMBEDDING_API_KEY`，保留
`RAG_EMBEDDING_PROVIDER=openai`。不要混用 hash 和真实 embedding 创建的数据；
真实 AI 验证前不要上传测试文档到 hash 向量集合。

后端接口文档：http://localhost:18080/swagger-ui.html

初始账号：admin / admin123；user / user123。
这里只构建后端容器，原 Dockerfile 同样不包含 React 前端。

## 本地部署适配

- `docker/local/01-schema.sql` 来源于主 schema.sql，仅将 `CREATE INDEX IF NOT EXISTS`
  替换为 MySQL 8 支持的 `CREATE INDEX`，供新数据卷首次初始化使用，不要重复执行。
- 健康检查请求公开的 GET `/v3/api-docs`，避免 GET 登录接口返回 405。
- 数据库不映射宿主机端口。仅后端 18080 绑定 127.0.0.1。
- 本地数据库口令和 JWT 配置仅用于本机验证。
- 不带 `--env-file` 时会使用占位模型密钥，无法完成真实问答与简历解析。

## 状态与日志

```powershell
docker compose -f docker-compose.local.yml ps
docker compose -f docker-compose.local.yml logs --tail 100 app
docker compose -f docker-compose.local.yml stop
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
