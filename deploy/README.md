# 部署入口

以下命令均在项目根目录运行，环境文件仍位于项目根目录。

## 本地完整服务

复制 `.env.local.example` 为 `.env.local` 并配置，然后执行：

```powershell
mvn clean package
docker compose --env-file .env.local -f deploy/docker-compose.local.yml up -d --build
```

本地项目名保持 `wibecoding-local`。`Dockerfile.local` 复制根目录 `target/` 中的 JAR；对应专用 dockerignore 与 Dockerfile 同目录。

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

目录整理仅验证配置解析和路径，不代表已完成镜像构建或生产部署。
