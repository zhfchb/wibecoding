# 多阶段构建:Stage 1 用 Maven 构建,Stage 2 只跑 jar,镜像更小更安全
# 构建命令:docker build -t wibecoding-app:latest .
# 运行命令:docker run -p 8080:8080 --env-file .env.prod wibecoding-app:latest

# ---------- Stage 1:构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先只拷 pom.xml,利用 Docker 缓存加速依赖下载(依赖不变则走缓存)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 拷源码,打包(跳过测试,CI 已跑过)
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------- Stage 2:运行 ----------
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 拷构建产物
COPY --from=builder /build/target/*.jar app.jar

# 时区
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 非 root 用户运行(安全实践)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8080

# 健康检查(可选,需配合 Spring Boot Actuator 或自定义 /health)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

# 生产 profile,环境变量覆盖敏感配置
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
