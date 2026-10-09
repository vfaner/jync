# ============================================================================
# Jync 捷同 · 容器镜像(多阶段构建)
#   docker build -t jync:1.3.2 .
#   docker run -d -p 8080:8080 -v jync-data:/app/data -v jync-logs:/app/logs jync:1.3.2
# 元数据(H2 库文件)在 /app/data,日志在 /app/logs,升级镜像不丢数据。
# 详细部署说明见 docs/deploy-container.md。
# ============================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先只拷 pom 拉依赖:pom 不变时这一层走缓存,重复构建秒级完成
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# 容器内构建跳过测试(测试在 CI / 本地跑);finalName 固定为 jync,产物即 target/jync.jar。
# 注意:构建需要仓库根目录的 RELEASE_NOTES_v<版本>.md(copy-release-notes 会打进 jar,
# 供设置页「本版本更新内容」使用),.dockerignore 不能把它排掉。
RUN mvn -q -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy
# curl 仅用于 HEALTHCHECK
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin jync
WORKDIR /app
COPY --from=build /build/target/jync.jar /app/jync.jar
# data/logs 目录先建好并交给运行用户:挂载命名卷时 Docker 会继承这里的属主
RUN mkdir -p /app/data /app/logs && chown -R jync:jync /app
USER jync

EXPOSE 8080
VOLUME ["/app/data", "/app/logs"]

# JVM 按容器内存限额的 75% 取堆,配合 docker run -m / K8s resources.limits 使用
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# /login 是匿名可访问页面,返回 200 即视为存活
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/login || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/jync.jar"]
