# Jync 捷同 · 容器化部署指南（Docker / Kubernetes）

本文覆盖镜像构建、单机 Docker / Compose 部署、Kubernetes（Kustomize）部署、多实例横向扩容与升级排障。裸机 jar 部署见 [README 的部署步骤](../README.md#部署步骤)。

## 1. 镜像

仓库根目录的 `Dockerfile` 是多阶段构建：

- **构建阶段**：`maven:3.9-eclipse-temurin-17`，先拷 `pom.xml` 拉依赖（pom 不变时走层缓存），再拷 `src` 打包，产物统一为 `jync.jar`（与发布约定一致，版本号只在 pom 里）。
- **运行阶段**：`eclipse-temurin:17-jre-jammy`，只带 JRE；`curl` 仅用于 HEALTHCHECK；以非 root 用户 `jync`（uid 10001）运行。

```bash
docker build -t jync:1.3.2 .
```

构建时跳过测试（`-DskipTests`），测试请在 CI 或本地 `mvn clean test` 跑。

### 容器内路径约定

| 路径 | 内容 | 建议 |
|---|---|---|
| `/app/jync.jar` | 应用本体 | 随镜像升级 |
| `/app/data` | H2 元数据库（连接、项目、游标、同步锁、变更日志、元数据快照都在库里） | **必须持久化** |
| `/app/logs` | 运行日志 | 持久化或走 stdout |

`application.yml` 里这些是相对路径（`./data`、`./logs`），工作目录固定为 `/app`，所以挂卷即生效，无需改配置。

## 2. Docker 单机部署

```bash
docker run -d --name jync \
  -p 8080:8080 \
  -v jync-data:/app/data \
  -v jync-logs:/app/logs \
  -e TZ=Asia/Shanghai \
  --restart unless-stopped \
  jync:1.3.2
```

打开 <http://localhost:8080>，默认账号 `admin` / `view`，密码 `123456`，**登录后立即修改**。

## 3. Docker Compose

仓库根目录自带 `docker-compose.yml`（数据落在宿主机 `./data`、`./logs`）：

```bash
docker compose up -d --build
```

Compose 文件里已写好常用环境变量的注释示例（HTTPS Cookie、外部 MySQL），按需要放开。

## 4. Kubernetes 部署

清单在 `deploy/k8s/`，用 Kustomize 组织：

```bash
# 1) 构建并推送镜像到你的仓库
docker build -t registry.example.com/qqmu/jync:1.3.2 .
docker push registry.example.com/qqmu/jync:1.3.2

# 2) 改 deploy/k8s/kustomization.yaml 里的 images 变换指向你的仓库

# 3) 部署
kubectl apply -k deploy/k8s/
kubectl -n jync rollout status deploy/jync
```

要点：

- **replicas 默认 1**：默认元数据是 H2 文件库，PVC 为 ReadWriteOnce，多副本会抢挂载/抢文件锁。更新策略相应设为 `Recreate`。
- **探针**：startup / liveness / readiness 都打匿名可访问的 `GET /login`（应用未引入 actuator，登录页 200 即代表 Web 层与数据库可用）。
- **日志**：挂 `emptyDir`，`kubectl logs` 看 stdout 即可；需要留存就换成 PVC。
- **Ingress**：`ingress.yaml` 是可选件，域名/TLS/注解按集群情况改；没有 Ingress Controller 就从 `kustomization.yaml` 里移除。清单里已放宽 nginx-ingress 的代理超时到 300s——大表手动「立即同步」与连接测试可能超过默认的 60s。
- **安全上下文**：`runAsNonRoot: true`、`runAsUser: 10001`，与镜像内用户一致。

## 5. 环境变量

Spring 的 relaxed binding 让所有配置项都能用环境变量覆盖，无需改镜像或配置文件。常用的：

| 变量 | 对应配置 | 说明 |
|---|---|---|
| `TZ` | — | 容器时区，建议 `Asia/Shanghai` |
| `JAVA_OPTS` | — | JVM 参数，镜像默认 `-XX:MaxRAMPercentage=75`（按容器内存限额取堆） |
| `SERVER_PORT` | `server.port` | 容器内端口，默认 8080 |
| `JYN_COOKIE_SECURE` | `server.servlet.session.cookie.secure` | HTTPS 部署设 `true`，会话 Cookie 带 Secure 标记 |
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | 换外部元数据库（多实例的前提） |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 同上 | 元数据库账号 |
| `SYNC_CRYPTO_PASSWORD` / `SYNC_CRYPTO_SALT` | `sync.crypto-password` / `sync.crypto-salt` | **生产环境必改**：连接密码的加密密钥；改后旧密码无法解密，需在界面重填 |
| `SYNC_POLL_INTERVAL` | `sync.poll-interval` | 默认轮询间隔（毫秒） |
| `SYNC_AI_ENABLED` | `sync.ai.enabled` | `false` 可整体移除 AI 功能（纯内网合规场景） |
| `APP_UPDATE_CHECK_ENABLED` | `app.update-check.enabled` | 纯内网访问不了 GitHub 时设 `false` |

> 密钥类变量（`SYNC_CRYPTO_*`、`SPRING_DATASOURCE_PASSWORD`）在 K8s 里放 Secret，用 `secretKeyRef` 引用，不要写进清单明文。

## 6. 多实例部署

Jync 的同步正确性不依赖单实例，依赖的是**共享元数据库**：

1. **换外部元数据库**：H2 文件库的 `AUTO_SERVER=TRUE` 只支持同一台主机上的多进程共享；跨主机多副本必须把 `SPRING_DATASOURCE_URL` 指到外部 MySQL（建库 `CREATE DATABASE jync DEFAULT CHARACTER SET utf8mb4;`，首启自动建表）。
2. **调度对账**：Quartz 用的是每节点内存调度（RAMJobStore）。在任意节点上启动/停用项目后，其余节点的 `ScheduleReconciler` 会在 `sync.reconcile-interval-ms`（默认 30 秒）内把本地调度对齐到数据库里的 `enabled` 标志；调度触发时 `SyncJob` 还会再查一次开关，停用项目不会在任何节点偷跑。
3. **执行互斥**：同一项目同一时刻只会在一个节点执行——每轮同步前抢数据库里的分布式锁（`sync_task` 表的 `lock_owner` / `lock_expires_at` 字段，带 TTL，节点崩溃后锁自动过期，也可在界面上强制解锁），抢不到的节点跳过本轮。
4. 把 `deployment.yaml` 的 `replicas` 调大即可（此时 PVC 仅剩日志用途，可换 emptyDir）。

注意：对账只保证项目调度的**存在性**一致；轮询间隔/cron 的变更在保存它的那个节点立即生效，其他节点在该项目下次于本节点被调度时对齐。对同步语义无影响（锁互斥保证不会双跑）。

## 7. 升级与备份

```bash
# Docker：换镜像标签重建容器，数据卷不动
docker pull registry.example.com/qqmu/jync:1.3.3
docker compose up -d

# K8s：改 kustomization.yaml 的 newTag 后
kubectl apply -k deploy/k8s/
```

- 元数据表结构走 `ddl-auto: update` 自动演进，无需手工迁移。
- **升级前备份 `/app/data`**（H2 库文件）；停机期间源库的变更由游标机制在重启后自动补齐。
- 从旧版 SyncTool（≤ 1.2.x）升级：把旧 `synctool.mv.db` 放进 `/app/data`，首启时 `LegacyStoreMigration` 会自动改名接管。

## 8. 排障

| 现象 | 处理 |
|---|---|
| 容器反复重启，日志见 `./data/jync.mv.db` 锁冲突 | 同一 H2 卷被两个容器同时挂了；确保单副本，或多副本必须换外部数据库 |
| 页面能开但连接测试全部超时 | 容器网络到源/目标库不通；注意容器内 `localhost` 指容器自己，宿主机库要写 `host.docker.internal`（Docker Desktop）或宿主机实际 IP |
| K8s 里探针失败但本地正常 | 启动慢于 startupProbe 预算（默认 30×2s）；大元数据库首启建表较慢，调大 `failureThreshold` |
| HTTPS 下登录后立刻掉登录态 | 设 `JYN_COOKIE_SECURE=true` 且确认代理正确传递 `X-Forwarded-Proto` |
| 界面时间差 8 小时 | 容器缺 `TZ`，加 `TZ=Asia/Shanghai` |
| `docker build` 拉基础镜像超时 | 国内网络给 Docker 配 registry mirror（`/etc/docker/daemon.json` 或 OrbStack 设置），或先在能出网的机器上构建后 `docker save` / 推私仓 |
