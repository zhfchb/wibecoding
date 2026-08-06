# 环境配置说明

> 本文件登记本项目所有外部服务、端口、凭据、启动方式与配置文件分层。
> 中间件默认通过 `docker-compose.yml` 管理；MySQL 因本机已装(宝塔管理)暂用本地实例,M6 容器化时再统一。

## 1. 服务总览

| 服务 | 来源 | 容器名/进程 | 端口 | 用途 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Qdrant | `docker compose` | wibecoding-qdrant | 6333(HTTP)/6334(gRPC) | 向量数据库，稠密+稀疏混合检索 | ✅ 已用 |
| MySQL | **本地宝塔管理** | mysqld.exe(系统服务) | 3306 | 文档元数据 + 对话历史 | ✅ 已用 |
| Redis | `docker compose` | wibecoding-redis | 6379 | 多轮会话上下文 + TTL 过期 | ✅ 已用 |
| App | Dockerfile | wibecoding-app | 8080 | Spring Boot 应用本体 | ✅ 已用 |
| 硅基流动 Rerank | 外部 API | - | 443 | BGE-Reranker-v2-M3 精排（可选） | ✅ 已用 |

> 说明 1：MySQL 使用本机宝塔管理的 MySQL 8.0,安装目录 `E:\software\BtSoft\mysql\MySQL8.0`,版本 8.0.29。`docker-compose.yml` 保留 MySQL 服务定义(已注释)作为备选,如需切回 Docker MySQL,先停本地 MySQL 服务再取消注释。
> 说明 2：M7 混合检索方案已确定 —— 不引入 Elasticsearch，改用 **Qdrant 原生 sparse vectors + `/points/query` 的 RRF 融合**；稀疏向量由客户端 `HashingSparseModel`（TF-IDF 风格）生成，dev/prod 一致，无需额外服务。Rerank 走硅基流动 `/rerank`（BGE-Reranker-v2-M3），无独立 Key 时自动降级为 Noop。

## 2. 快速启动

### 2.1 启动全部外部服务

```bash
# Docker 部分(Qdrant + Redis),MySQL 由宝塔面板管理,需单独启动
docker compose up -d

# 本地 MySQL 由宝塔面板管理,通常已设为开机自启;若未启动,在宝塔面板「软件商店 → MySQL → 启动」
```

### 2.2 查看服务状态

```bash
docker compose ps                              # Qdrant + Redis
& "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uagent -pagent123 agent -e "SELECT 1;"   # MySQL
```

### 2.3 停止服务

```bash
docker compose down                            # Qdrant + Redis
# MySQL 在宝塔面板「软件商店 → MySQL → 停止」
```

### 2.4 停止并清除数据卷（⚠️ 会丢失数据）

```bash
docker compose down -v
```

### 2.5 单独启动某个服务

```bash
docker compose up -d qdrant
docker compose up -d redis
# MySQL 见 §3.2

## 3. 服务详情

### 3.1 Qdrant 向量数据库

| 项 | 值 |
| --- | --- |
| 镜像 | `qdrant/qdrant:latest`（当前 1.19.x） |
| HTTP 端口 | 6333 |
| gRPC 端口 | 6334 |
| 数据卷 | `qdrant_storage` → `/qdrant/storage` |
| 默认集合 | `knowledge` |
| 健康检查 | `GET http://localhost:6333/` 返回版本 JSON |

**配置键**（`application.yml`）：
- `rag.vector-store.type=qdrant`
- `rag.vector-store.qdrant-url=http://localhost:6333`
- `rag.vector-store.collection-name=knowledge`
- `rag.hybrid.enabled=true` / `rag.hybrid.recall-top-k=20` / `rag.hybrid.rrf-k=60`：混合检索（dense+sparse+RRF）
- `rag.rerank.enabled` / `rag.rerank.provider=siliconflow` / `rag.rerank.model-name=BAAI/bge-reranker-v2-m3`：Rerank 精排

> ⚠️ **M7 schema 变更**：集合由「默认未命名向量」改为命名向量 `dense`(Cosine) + `sparse`(sparse_vectors)。升级到 M7 后，旧 `knowledge` 集合不兼容，需删除重建：
> `Invoke-RestMethod -Method Delete -Uri "http://localhost:6333/collections/knowledge"`，之后首次上传文档会按新 schema 自动创建。

### 3.2 MySQL 8.0(本地宝塔管理)

| 项 | 值 |
| --- | --- |
| 部署方式 | 本地宝塔面板管理(非 Docker) |
| 安装目录 | `E:\software\BtSoft\mysql\MySQL8.0` |
| mysql.exe | `E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe` |
| 版本 | 8.0.29 |
| 端口 | 3306 |
| 数据库名 | `agent`(utf8mb4 / utf8mb4_unicode_ci) |
| 业务账号 | `agent` / `agent123`(允许 localhost + %) |
| root 账号 | `root`(密码见宝塔面板,不入仓库) |
| 系统服务名 | MySQL(宝塔管理,通常开机自启) |

**配置键**（`application.yml`）：
- `spring.datasource.url=jdbc:mysql://localhost:3306/agent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai`
- `spring.datasource.username=agent`
- `spring.datasource.password=agent123`

**首次初始化**(只需执行一次,已执行过):
```powershell
# 1. 用 root 创建 agent 库 + agent 用户
Get-Content "e:\Projects\wibecoding\scripts\init-local-mysql.sql" -Raw | & "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uroot -p<root密码>

# 2. 用 agent 账号导入表结构
Get-Content "e:\Projects\wibecoding\src\main\resources\schema.sql" -Raw | & "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uagent -pagent123 agent
```

**初始化脚本**:`scripts/init-local-mysql.sql`(创建 agent 库 + agent@localhost / agent@% 用户,GRANT ALL ON agent.*)。

**建表脚本**：`src/main/resources/schema.sql`(document + chat_history + sys_user 三张表)
- H2 测试环境自动执行
- 本地 MySQL 已执行,如需重置:`DROP TABLE document, chat_history, sys_user;` 后重新执行 schema.sql

**用户初始化脚本**:`scripts/init-users.sql`(插入默认 admin/admin123 + user/user123)
```powershell
Get-Content "e:\Projects\wibecoding\scripts\init-users.sql" -Raw |
  & "E:\software\BtSoft\mysql\MySQL8.0\bin\mysql.exe" -uagent -pagent123 agent
```

**默认账号**(仅本地开发用):

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| admin | admin123 | ADMIN |
| user | user123 | USER |

**切回 Docker MySQL 的步骤**(M6 容器化时使用):
1. 在宝塔面板停掉本地 MySQL 服务
2. 取消 `docker-compose.yml` 里 mysql 服务的注释
3. `docker compose up -d mysql`
4. 用 root 重新建 agent 库 + agent 用户(或让容器自动建)`docker exec -i wibecoding-mysql mysql -uagent -pagent123 agent < src/main/resources/schema.sql`

### 3.3 Redis 7

| 项 | 值 |
| --- | --- |
| 镜像 | `redis:7-alpine` |
| 端口 | 6379 |
| 数据卷 | `redis_data` → `/data` |
| 会话 key 前缀 | `chat:session:{sessionId}` |
| 会话 TTL | 3600 秒（1 小时，可配） |

**配置键**（`application.yml`）：
- `spring.data.redis.host=localhost`
- `spring.data.redis.port=6379`
- `chat.session.store-type=redis`（测试环境用 `memory`）
- `chat.session.ttl-seconds=3600`
- `chat.session.max-history=20`

## 4. 配置文件分层

| 文件 | 用途 | 是否提交仓库 |
| --- | --- | --- |
| `application.yml` | 默认骨架配置 | ✅ 提交 |
| `application-dev.yml` | 本地开发，含真实 API Key | ❌ gitignore |
| `application-test.yml` | 测试 profile，dummy Key + H2 + memory session | ✅ 提交 |
| `application-prod.yml` | 生产 profile(Docker 部署),敏感配置走环境变量 | ✅ 提交 |
| `application-qdrant.yml` | Qdrant 集成测试 profile | ✅ 提交 |

**环境变量覆盖**（生产部署用）：
- `DEEPSEEK_CLIENT_API_KEY`：DeepSeek API Key
- `RAG_EMBEDDING_API_KEY`：硅基流动 BGE-M3 Embedding Key
- `SPRING_DATASOURCE_PASSWORD`：MySQL 密码
- `SPRING_DATA_REDIS_HOST`：Redis 主机
- `JWT_SECRET`：JWT 签名密钥(生产必须覆盖,至少 32 字符)
- `JWT_EXPIRATION_MS`：JWT 过期时间(毫秒)

## 5. 应用启动

### 5.1 本地开发

```bash
# 1. 启动 Docker 服务(Qdrant + Redis);MySQL 由宝塔管理,需单独确认已启动
docker compose up -d

# 2. 启动应用（使用 dev profile，含真实 key）
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 5.2 测试

```bash
# 单元测试（无需外部服务，用 test profile + H2 + memory）
mvn clean test

# Qdrant 集成测试（需先 docker compose up -d qdrant）
$env:QDRANT_URL="http://localhost:6333"; mvn test "-Dtest=QdrantVectorStoreTest"
```

### 5.3 生产部署(M6 已完成 ✅)

**一键全栈部署**(app + qdrant + redis + mysql):

```bash
# 1. 复制环境配置模板,填入真实 API key
cp .env.prod.example .env.prod
# 编辑 .env.prod 填入 DEEPSEEK_CLIENT_API_KEY / RAG_EMBEDDING_API_KEY / JWT_SECRET 等

# 2. 一键构建并启动全栈
docker compose -f docker-compose.prod.yml up -d --build

# 3. 查看状态
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app

# 4. 访问
# 应用:http://localhost:8080
# Swagger UI:http://localhost:8080/swagger-ui.html
```

**特性**:
- MySQL 带 healthcheck,app 等 MySQL 健康后才启动(`depends_on.condition: service_healthy`)。
- MySQL 首次启动自动建表(schema.sql + init-users.sql 挂载到 `/docker-entrypoint-initdb.d/`)。
- 所有敏感配置走 `.env.prod` 环境变量,不入仓库。
- Dockerfile 多阶段构建,镜像小(只含 JRE + jar),非 root 运行。

**停止/清理**:
```bash
docker compose -f docker-compose.prod.yml down           # 停服务
docker compose -f docker-compose.prod.yml down -v        # 停服务 + 删数据卷(⚠️ 丢数据)
```

### 5.4 CI(GitHub Actions,M6 已完成 ✅)

- 配置文件:`.github/workflows/ci.yml`
- 触发:push/PR 到 main/master
- 流程:Checkout → JDK 17 + Maven 缓存 → 编译 → 测试(test profile)→ 打包 → 上传 jar artifact(保留 7 天)
- 使用:把项目 push 到 GitHub 仓库后自动生效,在仓库 Actions 页面查看运行状态。

## 6. 端口占用速查

| 端口 | 服务 | 备注 |
| --- | --- | --- |
| 3306 | MySQL(本地宝塔管理) | mysqld.exe 系统服务,非 Docker |
| 6333 | Qdrant HTTP | |
| 6334 | Qdrant gRPC | |
| 6379 | Redis | |
| 8080 | Spring Boot 应用 | |
| 9999 | （测试 dummy） | application-test.yml 中 DeepSeek base-url 占位 |

## 7. 凭据速查

> ⚠️ 以下凭据仅用于本地开发，生产环境必须通过环境变量覆盖。root 密码不入仓库。

| 服务 | 账号 | 密码 |
| --- | --- | --- |
| MySQL（业务） | agent | agent123 |
| MySQL（root） | root | 见宝塔面板(本机默认 123456,仅本地用) |
| Redis | （无密码） | - |
| Qdrant | （无密码） | - |
| 应用默认账号-admin | admin | admin123(ADMIN 角色) |
| 应用默认账号-user | user | user123(USER 角色) |
| DeepSeek API | - | 见 `application-dev.yml` |
| 硅基流动 Embedding | - | 见 `application-dev.yml` |
| 硅基流动 Rerank | - | 默认复用 Embedding Key；`RAG_RERANK_API_KEY` 可独立配置 |
| JWT 签名密钥 | - | `jwt.secret`(application.yml,生产用 `JWT_SECRET` 覆盖) |

## 8. 后续规划

| 里程碑 | 涉及环境 | 说明 |
| --- | --- | --- |
| M7 RAG 优化 | Qdrant sparse + 硅基流动 Rerank | 已完成：Qdrant 原生混合检索（RRF）+ BGE-Reranker 精排，不引入 ES |
| M8 LangGraph4j | 复用 MySQL 或加 PostgreSQL | Checkpoint 持久化；倾向复用 MySQL 减少服务数量 |
