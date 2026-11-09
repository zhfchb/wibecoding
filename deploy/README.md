# 部署入口

以下命令均在项目根目录运行，环境文件仍位于项目根目录。

更新于2026-11-09。当前核心招聘流程已在本地真实模型环境验收；生产配置仍需单独部署验证。已有数据库先备份并按[迁移顺序](migrations/README.md)升级，初始化SQL不会自动更新已有数据卷。

## 本地完整服务

复制 `.env.local.example` 为 `.env.local` 并配置，然后执行：

```powershell
mvn clean package
docker compose --env-file .env.local -f deploy/docker-compose.local.yml up -d --build
```

本地项目名保持 `wibecoding-local`。`Dockerfile.local` 复制根目录 `target/` 中的 JAR；对应专用 dockerignore 与 Dockerfile 同目录。

前端单独启动：在`frontend`执行`npm ci`，设置`$env:API_PROXY_TARGET='http://127.0.0.1:18080'`后运行`npm run dev -- --host 127.0.0.1 --strictPort`，默认访问5173。真实向量配置与详细步骤见[根README](../README.md)。

## 生产配置

复制 `.env.prod.example` 为 `.env.prod` 并配置，然后执行：

```powershell
docker compose --env-file .env.prod -f deploy/docker-compose.prod.yml up -d --build
```

`Dockerfile` 在镜像内执行 Maven 构建。基础配置与生产配置显式保持项目名 `wibecoding`，避免仅因配置文件搬迁而改变默认命名卷前缀。已有部署若使用过其他项目名，继续用原来的 `-p` 或 `COMPOSE_PROJECT_NAME`。

## 其他配置

- `docker-compose.yml`：Qdrant、Redis 基础服务，MySQL 配置为历史注释参考。
- `docker-compose.ai.yml`：兼容 AI 服务叠加配置，与 local 配置联合使用。
- `local/01-schema.sql`：本地 MySQL 首次启动初始化脚本。

```powershell
docker compose -f deploy/docker-compose.yml up -d
docker compose --env-file .env.local -f deploy/docker-compose.local.yml -f deploy/docker-compose.ai.yml config --quiet
```

本地五服务及DeepSeek/硅基流动单样本招聘流程已验收，见[真实验收](../docs/acceptance/LIVE_RECRUITMENT_ACCEPTANCE.md)。生产Compose是配置模板，生产部署、性能及备份恢复演练尚未验收。
