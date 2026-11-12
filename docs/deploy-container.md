# Jync 捷同 · 容器化部署指南（Docker / Kubernetes）

本文覆盖镜像构建、单机 Docker / Compose 部署、Kubernetes（Kustomize）部署、多实例横向扩容与升级排障。裸机 jar 部署见 [README 的快速开始](../README.md#deploy)。

## 1. 镜像

仓库根目录的 `Dockerfile` 是多阶段构建：

- **构建阶段**：`maven:3.9-eclipse-temurin-17`，先拷 `pom.xml` 拉依赖（pom 不变时走层缓存），再拷 `src` 打包，产物统一为 `jync.jar`（与发布约定一致，版本号只在 pom 里）。
- **运行阶段**：`eclipse-temurin:17-jre-jammy`，只带 JRE；`curl` 仅用于 HEALTHCHECK；以非 root 用户 `jync`（uid 10001）运行。

```bash
docker build -t jync:2.1.0 .
```

构建时跳过测试（`-DskipTests`），测试请在 CI 或本地 `mvn clean test` 跑。

### 容器内路径约定

| 路径 | 内容 | 建议 |
|---|---|---|
| `/app/jync.jar` | 应用本体 | 随镜像升级 |
| `/app/data` | H2 元数据库（连接、项目、游标、同步锁、变更日志、元数据快照都在库里） | **必须持久化** |
| `/app/data/tls` | 应用内 HTTPS 的 `tls.properties` 边车（口令加密存放）、页面上传暂存的证书文件（`uploads/`），以及手填挂载路径时派生的 `keystore-*` | 随 `/app/data` 一起备份；不用应用内 TLS 时为空目录 |
| `/app/logs` | 运行日志 | 持久化或走 stdout |

`application.yml` 里这些是相对路径（`./data`、`./logs`），工作目录固定为 `/app`，所以挂卷即生效，无需改配置。

## 2. Docker 单机部署

```bash
docker run -d --name jync \
  -p 8080:8080 \
  -p 8443:8443 \
  -v jync-data:/app/data \
  -v jync-logs:/app/logs \
  -e TZ=Asia/Shanghai \
  --restart unless-stopped \
  jync:2.1.0
```

`-p 8443:8443` 只有用「应用内 TLS」（在「系统配置」上传证书或手填证书路径）时才需要；端口可在页面上改，改完同步改这里的映射。上传的密钥库落在数据卷 `/app/data/tls`，重启容器后生效。

**把证书挂载进容器、只让页面记地址**：`-v /etc/jync/tls:/etc/jync/tls:ro` 挂上宿主机证书，然后在「系统管理 → 系统配置 → TLS 证书」，在路径输入框里直接填 `/etc/jync/tls/...` 的绝对路径（输入框旁的「上传…」会把文件暂存进数据卷，适合证书不在宿主机挂载点的场景）。证书不复制进数据卷，容器删除重建后路径依然有效（边车在 `/app/data/tls`，随数据卷保留）；换证书只需替换宿主机文件并重启容器。

**或者完全交给部署配置**：把证书挂载进容器并在环境变量里指路，页面变为只读，容器删除重建也不会丢：

```bash
docker run ... \
  -v /etc/jync/tls:/etc/jync/tls:ro \
  -e JYN_TLS_PEM_CERT_PATH=/etc/jync/tls/fullchain.pem \
  -e JYN_TLS_PEM_KEY_PATH=/etc/jync/tls/privkey.pem \
  jync:2.1.0
```

密钥库同理（`JYN_TLS_KEYSTORE_PATH` + `JYN_TLS_KEYSTORE_PASSWORD`）。一旦配置，系统配置页的 TLS 卡片变为只读，改证书只需替换宿主机文件并重启容器。详见[配置文档](configuration.md)「应用内 TLS」。

打开 <http://localhost:8080>，默认账号 `admin` / `view`，密码 `123456`，**首次登录必须先修改密码**（未修改前只能停留在改密页）。

## 3. Docker Compose

**内置 H2**：仓库根目录自带 `docker-compose.yml`（数据落在宿主机 `./data`、`./logs`）：

```bash
docker compose up -d --build
```

Compose 文件里已写好常用环境变量的注释示例（HTTPS Cookie、外部 MySQL），按需要放开。

**外部元数据库**：`deploy/` 下有三个可直接启动的示例，Jync 与数据库一起起，数据库 healthcheck 通过后才启动 Jync：

| 文件 | 元数据库 | 说明 |
|---|---|---|
| `deploy/docker-compose.mysql.yml` | MySQL 8.4 | JDBC 连接带 `allowPublicKeyRetrieval=true`（容器默认 `caching_sha2_password` + 明文连接） |
| `deploy/docker-compose.postgres.yml` | PostgreSQL 16 | — |
| `deploy/docker-compose.oracle.yml` | Oracle Database Free 23ai | 镜像数 GB，首次启动建库需 2~5 分钟；默认 PDB 为 `FREEPDB1` |

```bash
# 以 MySQL 为例；另外两个把文件名换掉即可
docker compose -f deploy/docker-compose.mysql.yml up -d --build
```

使用要点：

- 示例里的口令、`SYNC_CRYPTO_*` 都是占位值，**上线前必须替换**。`SYNC_CRYPTO_PASSWORD/SALT` 不设会落到发行包里公开的默认密钥（等于没加密）；设置后必须长期保持不变，否则库里已存的连接密码无法解密，多实例也要每个节点一致。
- 三个示例都映射 `8080:8080`，同时跑会端口冲突；只保留一个，或自行改端口。`name:` 已按数据库区分，不同示例的卷互不干扰。
- 外部库场景下 `/app/data` 不再装元数据，但示例仍挂了 `jync-data` 卷：应用内 HTTPS 证书（`/app/data/tls`）和备份需要它，不建议省掉。
- 生产环境建议用托管数据库：保留 `jync` 服务的 `SPRING_DATASOURCE_*` 环境变量，删掉对应的数据库服务即可。
- 已有 H2 数据要迁到这些示例：先在旧实例「系统管理 → 系统配置 → 配置包」导出，启动示例后再导入；直接启动只会得到一个空库，见[元数据库迁移](configuration.md#元数据库迁移)。
- Oracle 示例用社区维护的 `gvenzl/oracle-free` 镜像；宿主机为 arm64 且拉不到对应变体时，取消文件里 `platform: linux/amd64` 的注释用 amd64 模拟运行。

## 4. Kubernetes 部署

清单在 `deploy/k8s/`，用 Kustomize 组织：

```bash
# 1) 构建并推送镜像到你的仓库
docker build -t registry.example.com/qqmu/jync:2.1.0 .
docker push registry.example.com/qqmu/jync:2.1.0

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
| `JYN_TRUSTED_PROXIES` | `app.security.trusted-proxies` | 可信反向代理地址（逗号分隔，支持 CIDR）；只有这些对端的 `X-Forwarded-Proto` 会被采信。代理终止 TLS 时**必须**配置，否则 Jync 认为连接是明文。**只能写在配置文件/环境变量里，页面不提供编辑入口** |
| `JYN_REQUIRE_HTTPS` | `app.security.require-https` | `true` 时非 TLS 请求返回 403（不带 Cookie 的 `GET /login`，以及没有可用 HTTPS 端点时的回环应急通道除外；应用内证书在跑时回环也按规则处理）。不设时由「系统管理 → 系统配置」页面决定，改完即时生效；一旦显式设置（含 `false`），页面开关变为只读。直连 8080 的部署不要在页面上打开；配置见[配置文档](configuration.md)「HTTPS 与等保部署」 |
| `JYN_TLS_ENABLED` | `app.tls.enabled` | 默认 `true`。设为 `false` 时不读取证书、不加 HTTPS 连接器——用于证书损坏或 8443 被占导致启动失败时救回实例 |
| `JYN_TLS_DIRECTORY` | `app.tls.directory` | 边车与派生密钥库目录，默认 `./data/tls`（即 `/app/data/tls`）；页面上传的密钥库也放这里。**证书不在配置包里**，换机或迁移时重新上传/重存路径，备份数据卷即可包含 |
| `JYN_TLS_REDIRECT_HOST` | `app.tls.redirect-host` | HTTP→HTTPS 重定向固定使用的主机名；留空沿用请求 `Host`（严格校验，非法值 400）。应用内 TLS 配置见[配置文档](configuration.md)「HTTPS 与等保部署」 |
| `JYN_TLS_KEYSTORE_PATH` / `JYN_TLS_KEYSTORE_PASSWORD` | `app.tls.keystore-path` / `app.tls.keystore-password` | 配置文件模式：挂载的密钥库路径与口令。指定后启动即读取、页面只读，容器重建不丢；与 PEM 二选一 |
| `JYN_TLS_PEM_CERT_PATH` / `JYN_TLS_PEM_KEY_PATH` | `app.tls.pem-cert-path` / `app.tls.pem-key-path` | 配置文件模式：PEM 证书链与（可选的）私钥路径；启动时在内存封装成临时 PKCS#12，明文私钥不落盘 |
| `JYN_TLS_HTTPS_PORT` / `JYN_TLS_REDIRECT_HTTP` | `app.tls.https-port` / `app.tls.redirect-http` | 配置文件模式下的 HTTPS 端口（默认 8443）与 HTTP→HTTPS 跳转（默认 `true`） |
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | 换外部元数据库（多实例的前提；已验证 MySQL 8 / PostgreSQL 16 / Oracle 23）。换库后按[元数据库迁移](configuration.md#元数据库迁移)导入配置包，否则新库是空的 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 同上 | 元数据库账号 |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `spring.datasource.driver-class-name` | 驱动类；能按 URL 推断时可省略 |
| `JYN_RESET_LOGIN` | `app.login.reset-on-startup` | 应急开关：关闭本地登录后外部认证不可用时，设为 `true` 启动一次即恢复本地登录并保存；修好后去掉 |
| `JYN_METRICS_TOKEN` | `metrics.prometheus-token` | Prometheus 抓取 `/api/metrics/prometheus` 的 Bearer token。不设则端点返回 404；设置后用 `Authorization: Bearer <token>` 访问。建议放 Secret |
| `SYNC_CRYPTO_PASSWORD` / `SYNC_CRYPTO_SALT` | `sync.crypto-password` / `sync.crypto-salt` | 可选：连接密码的加密密钥。不设则首次启动生成并写入数据卷里的 `<库名>.key`（备份数据卷即已包含）；从旧版本升级上来的部署**不要改** |
| `SYNC_POLL_INTERVAL` | `sync.poll-interval` | 默认轮询间隔（毫秒） |
| `SYNC_AI_ENABLED` | `sync.ai.enabled` | `false` 可整体移除 AI 功能（纯内网合规场景） |
| `APP_UPDATE_CHECK_ENABLED` | `app.update-check.enabled` | 纯内网访问不了 GitHub 时设 `false`（也可在「系统配置 → 更新检查」关闭；显式设置时页面开关只读） |
| `APP_UPDATE_CHECK_MATCH_ARTIFACT` | `app.update-check.match-artifact` | 版本号相同时是否再比对安装包；镜像出口对 Gitee HEAD 请求限流、导致升级提示偶发时设 `false` |

> 密钥类变量（`SYNC_CRYPTO_*`、`SPRING_DATASOURCE_PASSWORD`）在 K8s 里放 Secret，用 `secretKeyRef` 引用，不要写进清单明文。**多实例（元数据库换成外部 MySQL 等）时尤其要显式设置 `SYNC_CRYPTO_*`**：自动生成的密钥依附于本地 H2 文件，外部元数据库场景下没有这个文件，不设就只能用发行包里的公开默认值。

## 6. 多实例部署

Jync 的同步正确性不依赖单实例，依赖的是**共享元数据库**：

1. **换外部元数据库**：H2 文件库的 `AUTO_SERVER=TRUE` 只支持同一台主机上的多进程共享；跨主机多副本必须把 `SPRING_DATASOURCE_URL` 指到外部数据库。已验证组合为 MySQL 8、PostgreSQL 16、Oracle 23（MySQL 建库用 `CREATE DATABASE jync DEFAULT CHARACTER SET utf8mb4;`），首启由 `ddl-auto: update` 自动建表。
2. **调度对账**：Quartz 用的是每节点内存调度（RAMJobStore）。在任意节点上启动/停用项目后，其余节点的 `ScheduleReconciler` 会在 `sync.reconcile-interval-ms`（默认 30 秒）内把本地调度对齐到数据库里的 `enabled` 标志；调度触发时 `SyncJob` 还会再查一次开关，停用项目不会在任何节点偷跑。
3. **执行互斥**：同一项目同一时刻只会在一个节点执行——每轮同步前抢数据库里的分布式锁（`sync_task` 表的 `lock_owner` / `lock_expires_at` 字段，带 TTL，节点崩溃后锁自动过期，也可在界面上强制解锁），抢不到的节点跳过本轮。
4. 把 `deployment.yaml` 的 `replicas` 调大即可（此时 PVC 除日志外只剩应用内 TLS 证书目录，可换 emptyDir 并使用 Ingress 终止 TLS）。

注意：对账只保证项目调度的**存在性**一致；轮询间隔/cron 的变更在保存它的那个节点立即生效，其他节点在该项目下次于本节点被调度时对齐。对同步语义无影响（锁互斥保证不会双跑）。

**应用内 TLS 的页面配置是节点本地的**：边车和上传的证书存在各副本自己的 `/app/data/tls`，在一个副本的页面（经负载均衡落到哪个节点不确定）保存只会写到那个节点。要么把 `JYN_TLS_DIRECTORY` 指到所有副本共享的卷并设置 `JYN_TLS_REDIRECT_HOST`；要么把同一个 Secret 挂载到每个副本，各节点在页面里手填一次路径；最稳妥是完全走配置文件模式：设置 `JYN_TLS_KEYSTORE_PATH` / `JYN_TLS_PEM_CERT_PATH` 等变量，页面只读、实例重建不丢。K8s 多副本一般由 Ingress 终止 TLS，此时各副本设 `JYN_TLS_ENABLED=false` 即可。

多实例场景请显式设置 `SYNC_CRYPTO_PASSWORD` / `SYNC_CRYPTO_SALT`（见上一节）：自动生成的密钥保存在本地 `<库名>.key`，外部元数据库场景下没有这个文件。若确实在同一台机器上用同一份 H2 数据目录起了多个进程，升级后的首次启动只有一个进程会执行密钥轮换，其余进程会在轮换期间拒绝启动并提示稍后重试（否则它会一直使用公开默认密钥加密新凭据）；轮换完成后重新启动即可。

## 7. 升级与备份

```bash
# Docker：换镜像标签重建容器，数据卷不动
docker pull registry.example.com/qqmu/jync:2.1.0
docker compose up -d

# K8s：改 kustomization.yaml 的 newTag 后
kubectl apply -k deploy/k8s/
```

- 元数据表结构走 `ddl-auto: update` 自动演进，无需手工迁移。
- 自 v2.0.0 起内嵌 H2 固定为 2.1.214，可直接打开 1.x 写在 `/app/data/jync.mv.db` 的旧格式文件；**不要把镜像里的 H2 换成 2.2+**，否则旧库会报 `Unsupported database file version`。
- **升级前备份 `/app/data`**（H2 库文件）；停机期间源库的变更由游标机制在重启后自动补齐。
- 从旧版 SyncTool（≤ 1.2.x）升级：把旧 `synctool.mv.db` 放进 `/app/data`，首启时 `LegacyStoreMigration` 会自动改名接管。

## 8. 排障

| 现象 | 处理 |
|---|---|
| 容器反复重启，日志见 `./data/jync.mv.db` 锁冲突 | 同一 H2 卷被两个容器同时挂了；确保单副本，或多副本必须换外部数据库 |
| 页面能开但连接测试全部超时 | 容器网络到源/目标库不通；注意容器内 `localhost` 指容器自己，宿主机库要写 `host.docker.internal`（Docker Desktop）或宿主机实际 IP |
| K8s 里探针失败但本地正常 | 启动慢于 startupProbe 预算（默认 30×2s）；大元数据库首启建表较慢，调大 `failureThreshold` |
| HTTPS 下登录后立刻掉登录态 | 设 `JYN_COOKIE_SECURE=true` 且确认代理正确传递 `X-Forwarded-Proto` |
| 启动失败，日志有 `ConnectorStartFailedException` 或 `Address already in use` | 应用内 TLS 的 HTTPS 端口被占（重复副本、旧容器未退，或与 8080 填成同一端口）。以 `JYN_TLS_ENABLED=false` 启动一次，在「系统配置」改端口或删除证书，再正常重启 |
| 容器起不来，日志说密钥库不可读 / 摘要不符 / 缺少私钥 | 证书文件被替换或边车损坏。同上，用 `JYN_TLS_ENABLED=false` 救回实例后在页面上重新上传；手填路径的先确认挂载点文件存在且可读，再重新保存路径 |
| 界面时间差 8 小时 | 容器缺 `TZ`，加 `TZ=Asia/Shanghai` |
| `docker build` 拉基础镜像超时 | 国内网络给 Docker 配 registry mirror（`/etc/docker/daemon.json` 或 OrbStack 设置），或先在能出网的机器上构建后 `docker save` / 推私仓 |
