# 配置、调度与运维细节

README 只保留上手所需的最少信息；参数、调度规则、列清洗与统一认证的完整说明在这里维护。部署拓扑与排障另见 [容器部署说明](deploy-container.md) 和[部署与运维手册（PDF）](https://jync.qqmu.com/docs/jync-ops-guide.pdf)。

## 性能调优

全量阶段每张表、每个 worker 打印一行吞吐日志，把"慢"拆成三个可操作的占比：

```text
table=orders thread=full-load-orders rows=500000 changed=500000 elapsed=2.3s rows/s=216000.0 read=38% write=52% commit=10% batches=250 rowsPerBatch=2000
```

`read%` 高先查源库游标与网络；`write%` 高先查目标库约束、索引与大字段；`commit%` 高先加大批量减少提交次数。多数场景只需调这四个参数：

| 参数 | 默认 | 建议 | 作用 |
|---|---|---|---|
| `sync.full-load-batch-size` | `2000` | 1000–5000 | 每批行数；越大提交越少，回滚与内存成本越高 |
| `sync.full-load-fetch-size` | `2000` | 1000–5000 | 源端结果集批量；大字段表调小 |
| `sync.full-load-parallelism` | `2` | 1–4 | 单表并行 worker，受连接池约束；1→2 收益最大 |
| `sync.chunk-size` | `100000` | 5 万–20 万 | 每块行数，也是崩溃后最多重读的行数 |

容器单机 50 万行实测：空表 `INSERT` 约 21 万行/秒（`MERGE` 约 8.4 万）；并行度 1 / 2 / 4 对应 11.7 万 / 18.4 万 / 21.6 万行/秒；延迟建二级索引约快 47%。基准只说明方向，实际取决于磁盘、网络、表宽与并发负载。

> 完整的日志字段说明、瓶颈判断表、连接池硬约束与分步调优见[部署与运维手册](https://jync.qqmu.com/docs/jync-ops-guide.pdf)；性能曲线与采样配置见[用户操作手册](https://jync.qqmu.com/docs/jync-user-guide.pdf)。

## 调度设置

调度决定"增量多久追一次"，分三层：全局默认、任务级覆盖、日志 CDC 的常驻消费。

- **全局默认** `sync.poll-interval`（默认 2000 毫秒）；任务详情页可单独填写轮询间隔或 Cron，留空即继承全局值。调度在任务启动后才注册，运行中的任务需先停止才能修改。
- **轮询间隔**：实际生效范围 **200ms – 24h**。间隔模式错过的触发直接丢弃——积压的十次 2 秒轮询没有意义，下一轮本来就会看到全部变化。
- **Cron**：Quartz 语法，**6 段或 7 段，秒在最前**，填写后优先于轮询间隔；保存与启动时都会校验。按服务器时区求值，部署时请固定 `-Duser.timezone` 或容器 `TZ`；错过的触发会在有空闲线程时补跑一次。

```text
0/2 * * * * ?        每 2 秒
0 0/5 * * * ?        每 5 分钟
0 30 2 * * ?         每天 02:30
0 0 8 ? * MON-FRI    工作日 08:00
```

- **启动即跑一轮**：任务启动后立即执行一次，再按调度运行，不必等第一个间隔。
- **立即同步**：不改变启用状态，从上次成功位置补跑一轮；运行中点击会合并为一轮补跑。
- **停止同步**：取消调度，并让正在执行的一轮在下一个已提交批次边界安全退出，随后释放同步锁。
- **CDC 任务没有 Quartz 调度**：长驻消费者持续回放，页面隐藏调度卡片；空闲挖掘间隔按引擎单独配置。
- **多实例**：Quartz 使用内存存储，节点启动时按数据库里的启用标志重建调度；对账线程每 30 秒对齐一次，实际执行由数据库租约锁互斥。

## 列清洗

在**写入目标库之前**对字符串列做固定规则处理，源库数据不受影响。

| 规则 | 作用 | 示例 |
|---|---|---|
| `TRIM` | 去首尾空白（Unicode 空白） | `"  张三 "` → `"张三"` |
| `UPPER` | 转大写 | `"ab-01"` → `"AB-01"` |
| `LOWER` | 转小写 | `"AB-01"` → `"ab-01"` |
| `BLANK_TO_NULL` | 空串或纯空白转 `NULL` | `"   "` → `NULL` |
| `STRIP_PREFIX` | 去掉一个固定前缀 | 前缀 `CN-`：`"CN-001"` → `"001"` |
| `STRIP_SUFFIX` | 去掉一个固定后缀 | 后缀 `.csv`：`"a.csv"` → `"a"` |
| `REGEX_REPLACE` | 用正则替换全部匹配 | `\s+` → 空格；`^(\d{4})-` → `$1` |
| `MASK_PHONE` | 保留前 3 位与后 4 位，中间遮盖 | `"13800138000"` → `"138****8000"` |
| `MASK_ID_CARD` | 保留前 6 位与后 4 位 | `"110101199003071234"` → `"110101********1234"` |
| `MASK_BANK_CARD` | 只保留后 4 位 | `"6222020200112233445"` → `"***************3445"` |
| `MASK_NAME` | 保留首字符 | `"张三丰"` → `"张**"` |
| `MASK_EMAIL` | 保留本地部分首字符与完整域名 | `"alice@example.com"` → `"a***@example.com"` |
| `HASH_SHA256` | SHA-256 十六进制摘要，可带盐值 | `"alice"` → 64 位十六进制 |

- 规则按"表 + 列"配置，表名与列名**大小写不敏感**；同一张表内按保存顺序依次执行（先 `TRIM` 再 `BLANK_TO_NULL`，纯空白才会变成 `NULL`）。
- **只处理字符串值**，数字与日期原样通过；全量、增量轮询与 CDC 回放走同一套规则。
- 被清洗的列会强制整列物化读取，单值超过 `sync.lob.force-inline-max-bytes`（默认 1MB）会明确报错并指出表、列与配置键。
- `STRIP_PREFIX` / `STRIP_SUFFIX` / `REGEX_REPLACE` 必须填写参数；正则按 Java 语义在保存时校验，非法表达式与替换串直接拒绝。
- 掩码是**格式保留**的：手机号、身份证、银行卡、姓名的结果与原文等长，邮箱为「本地部分首字符 + `***` + 完整域名」，可能比原值略短；短于可保留长度时整串遮盖（手机号 ≤7 位、身份证 ≤10 位、银行卡 ≤8 位、姓名 1 个字符）。它只降低目标库里的可读性，不改变字段类型。
- `HASH_SHA256` 输出固定 **64 个字符**的十六进制摘要，目标列长度不足 64 会在写入时报错；它是**确定性**的：相同输入与相同盐值跨行、跨批次得到相同摘要，写入目标后仍可按该列关联。盐值明文保存在项目配置里，只用于区分用途、避免与其它系统同值互撞，**不能防字典攻击**；需要密钥化假名化时应在上游完成。
- 掩码与哈希同样**只改写目标库**。主键、游标或参与行数核对/内容校验的列被改写后，工具无法再用目标值回源定位；相关限制见[已知限制](known-limitations.md)。

## Redis 缓存目标

当项目的「目标类型」选 Redis 时，投影按**项目**配置，不写进 `application.yml`，也不属于 Redis 连接本身——同一个 Redis 目标可以被多个项目共用，每个项目必须有自己的前缀。这些项保存在项目的 `syncConfig.redisProjection` 里：

| 字段 | 默认 | 说明 |
|---|---|---|
| `keyPrefix` | `jync:<项目ID>:` | 所有键的前缀，保存时自动补结尾 `:`；同一 Redis 目标上不同项目不得相同 |
| `valueFormat` | `JSON` | `JSON`：每行一个 JSON 字符串；`HASH`：每行一个 Hash，field = 列名 |
| `ttlSeconds` | `0` | 键过期秒数；`0` = 不过期。用于让孤儿键自然消失 |
| `excludedColumns` | 空 | 不写入 Redis 的列，大小写不敏感；LOB/二进制默认排除 |
| `maxValueBytes` | `1048576` | 单行投影上限（4KB–16MB）；超限行跳过并计数，不截断、不停机 |
| `maxCommandsPerTransaction` | `10000` | 单个 CDC 事务缓冲的管道命令上限，超过先执行一批再继续 |

> Redis 目标只支持日志 CDC：保存时会拒绝 `POLLING` / `FULL_COMPARE`（`error.project.redis.cdcOnly`），结构同步、索引/视图/存储过程同步对 Redis 无意义并强制关闭。键值语义、孤儿键、HASH 布局漂移与集群行为见[已知限制](known-limitations.md) 12 节。

## 常用配置

`application.yml` 中最常用的 `sync.*` 键（完整清单见[用户操作手册](https://jync.qqmu.com/docs/jync-user-guide.pdf)）：

| 键 | 默认 | 说明 |
|---|---|---|
| `sync.poll-interval` | `2000` | 全局轮询间隔（毫秒） |
| `sync.batch-size` | `500` | 增量轮询与 CDC 回放的 JDBC 批次行数 |
| `sync.fetch-size` | `1000` | 增量读取的结果集批量 |
| `sync.full-load-batch-size` | `2000` | 首次全量每批行数 |
| `sync.full-load-parallelism` | `2` | 单表全量并行 worker 数，受连接池约束 |
| `sync.chunk-size` | `100000` | 全量每块行数；`0` 关闭分块 |
| `sync.max-pool-size` | `5` | 每个数据库连接池上限 |
| `sync.crypto-password` / `crypto-salt` | 留空自动生成 | 连接密码与告警地址的加密密钥 |
| `sync.ai.enabled` | `true` | 置 `false` 则 AI 菜单与接口一并下线 |
| `app.update-check.enabled` | `true` | 新版本检查；关闭后不发起外网请求 |
| `metrics.enabled` | `true` | 性能采样总开关，关闭不影响同步链路 |
| `sync.content-audit-interval-ms` | `900000` | 内容级校验间隔（15 分钟）；`0` 关闭。手工「检查内容」不受此值限制 |
| `sync.content-audit-sample-size` | `50` | 每表抽样行数，范围 2–200 |
| `sync.content-audit-max-rows` | `2000000` | 源行数估计超过此值的表跳过内容校验；`0` 不设上限 |
| `sync.content-audit-max-tables-per-cycle` | `1` | 每个同步周期最多校验的表数；`0` 不限制 |
| `sync.content-audit-timeout-seconds` | `60` | 单次内容校验查询的语句超时（秒） |

> 内容校验只报告差异，不修复目标。它要求表有主键、走增量策略、首次全量已完成，并默认排除 LOB/JSON/XML 等不可比列；抽样与排除规则见[已知限制](known-limitations.md) 3.7 节。

日志 CDC 相关（不启用 CDC 可忽略）：

| 键 | 默认 | 说明 |
|---|---|---|
| `sync.cdc.server-id-offset` | `612000` | MySQL 假 replica server id 基数，实际 id = 偏移 + 任务 ID |
| `sync.cdc.lock-ttl-ms` | `30000` | CDC 消费者租约时长 |
| `sync.cdc.supervise-interval-ms` | `10000` | 各节点巡检并抢占 CDC 租约的周期 |
| `sync.cdc.connect-timeout-ms` | `10000` | 连接源库的超时时间 |
| `sync.cdc.reconnect-backoff-ms` | `1000` | 断线重连的初始退避 |
| `sync.cdc.reconnect-backoff-max-ms` | `60000` | 断线重连的退避上限 |
| `sync.cdc.max-transaction-rows` / `max-transaction-bytes` | `1000000` / `512 MiB` | 单个源事务的缓冲上限，超限按非瞬态失败停车；`0` 不限 |
| `sync.cdc.logminer.batch-size` | `10000` | LogMiner 单批行数 |
| `sync.cdc.logminer.idle-poll-ms` | `1000` | LogMiner 真空闲休眠 |
| `sync.cdc.logminer.missing-lob-policy` | `skip` | 行外 LOB 无法还原时：`skip` 计数继续，`fail` 停车 |
| `sync.cdc.pg.idle-poll-ms` | `500` | PostgreSQL 逻辑解码真空闲休眠 |
| `sync.cdc.pg.wal-retention-warn-bytes` | `4294967296` | 复制槽滞留 WAL 的面板告警阈值（4 GiB） |
| `sync.cdc.mssql.idle-poll-ms` | `1000` | SQL Server CDC 真空闲休眠 |
| `sync.cdc.change-window-ms` | `10000` | CDC 变更日志按表聚合窗口；`0` 关闭 |

> 连接密码、AI 密钥与告警 Webhook 地址以 AES-256 加密存储，`enc:` 前缀防止重复加密；密钥留空时首启自动生成并写入 `data/<库名>.key`。

## 元数据库迁移

Jync 的元数据（连接、同步任务、ETL、专属迁移、用户、系统设置）默认存在内置 H2 文件里。要换到 MySQL / PostgreSQL / Oracle，只改 `SPRING_DATASOURCE_*` 是不够的：新库是空的，而且新部署通常有自己的一套 `sync.crypto-*` 密钥——旧库里的 `enc:` 密文在新密钥下全部解不开。**导出配置包就是补上这一步。**

流程：

1. 旧实例进入「系统管理 → 系统配置 → 配置包」，输入口令导出。包用 AES-256-GCM 加密，口令至少 8 位；勾选「包含同步进度与游标」可把断点一起带走。
2. 停旧实例，把新库的连接写到环境变量：

   ```bash
   SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/jync
   SPRING_DATASOURCE_USERNAME=jync
   SPRING_DATASOURCE_PASSWORD=...
   SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
   # 强烈建议显式指定；不设则落到发行包的公开默认密钥
   SYNC_CRYPTO_PASSWORD=...
   SYNC_CRYPTO_SALT=...
   ```

3. 启动新实例。`ddl-auto: update` 会自动建出全部元数据表。
4. 在「系统配置 → 配置包」导入刚才的包。导入成功后连接池、健康检查缓存和调度器会就地重新装载，**不需要再重启**。

几条硬性规则：

- **目标库必须是空的**：`database_config`、`redis_target`、`migration_connection`、`project`、`etl_*`、`migration_task` 等配置/状态表必须 0 行。`app_user`、`alert_config`、`oa_auth_config`、`system_setting` 例外：用户按用户名合并（同名保留目标端的口令哈希与角色），单例与设置按覆盖/upsert 处理。导入后必须至少还有一个管理员，否则整次导入回滚。
- **密钥随包走**：包内 `meta.json` 携带导出端的 `sync.crypto-*`（外面还有口令加密），导入时先用它解开 `enc:`，再用新部署的密钥重新加密。所以包里带着旧密钥——导入完成后请销毁包文件。
- **强制 HTTPS 的例外**：包里的 `security.require-https=true` 只在导入请求本身是 HTTPS 时保留；明文导入会写成 `false` 并在页面提示，否则新实例导入完就把管理员自己锁在 403 里。
- **不迁移的内容**：变更日志、审计、性能采样、ETL/迁移运行历史、`sync_task` 租约行都不进包，新库从零开始记。TLS 证书与口令存在数据目录（`data/tls`）而不是元数据库，同样不进包；换实例后在「系统配置」重新上传并重启。
- **迁移时机**：`chunk_checkpoint`（分块全量的表内断点）也不进包。键表在迁移后重跑一遍是幂等的；但**无主键表**如果在全量装载进行中迁移、目标表又已经写入了部分行，新实例没有断点标记，会把「目标非空」当作「别人填过」，回退到流式全量并按行插入，可能产生重复行。请尽量在全量装载结束后再迁移，或在迁移后核对无主键表的行数。
- **源库残留与名称冲突**：PostgreSQL 逻辑复制槽名为 `sync.cdc.pg.slot-prefix` + project id，崖山 YStream 服务名为 `JYNC_<project id>_<随机后缀>`。导入后 id 会重新分配，位点在换名后的资源上无法续接；导入时**基线完成标记不会被恢复**，首次启动会重新做一次全量加载。PG 槽名只含 project id，因此存在**名称冲突**：新 id 可能恰好等于旧实例上另一个项目的 id，新任务首次启动就会接到那个仍在运行的旧槽上。迁移前请先停掉旧实例的 CDC，或修改 `sync.cdc.pg.slot-prefix` 使名称不可能重叠；YStream 名带随机后缀，冲突概率低，但旧服务同样需要清理。确认不再使用后，请在源库手工清理旧槽（`SELECT pg_drop_replication_slot('<旧槽名>')`）和旧 YStream 服务，否则源库会持续保留 WAL/日志。
- **上限**：单包 ≤ 20 MB，解压后 ≤ 40 MB；超限时导出直接拒绝，请先清理变更日志或不要勾选状态。

「系统配置」页还有一个**元数据库连接测试器**：填入候选 JDBC 地址与驱动类，它会真的连一次并给出产品/版本和一段 `SPRING_DATASOURCE_*` 环境变量块，方便先验证网络与账号再重启。测试器只支持 MySQL、MariaDB、PostgreSQL、Oracle，**不保存任何东西**——哪个库生效始终只由 `SPRING_DATASOURCE_*` 决定，页面上没有第二份指针。

## HTTPS 与等保部署

连接密码、AI Key 在**落盘时**加密，但浏览器到 Jync 之间默认是明文 HTTP：登录口令、数据源密码、AI Key 都会出现在请求体里，等保测评与渗透测试会直接判为传输安全不合格。有两种让这一段链路加密的方式，二选一：

| 方式 | 证书在哪 | 适用 |
|---|---|---|
| 反向代理终止 TLS（推荐） | Nginx / 网关 / 云负载均衡 | 对外服务、多实例、已有统一的证书管理 |
| 应用内 TLS | 上传到「系统管理 → 系统配置」页 | 单实例内网、容器直连、不方便再加一层代理 |

### 反向代理终止 TLS（推荐）

Jync 只监听本机 8080，明文只存在于代理到 Jync 的回环/内网这一段。

| 键 | 默认 | 说明 |
|---|---|---|
| `app.security.trusted-proxies` | 空 | 可信代理地址（逗号分隔，支持 IPv4 / IPv6 / CIDR）；只有来自这些对端的 `X-Forwarded-Proto` 才会被采信，其余请求一律按直连处理 |
| `app.security.require-https` | `false` | 强制 HTTPS：判定为非 TLS 的请求直接返回 403。两个例外——不带 Cookie 的 `GET /login`（容器健康探针），以及**尚无可用 HTTPS 端点时**的回环地址（证书坏掉时的应急通道）；应用内证书已在提供服务时回环同样按规则跳转或拒绝，登录 POST 永远不放行 |

部署步骤：

1. 反向代理终止 TLS，并向下游传 `Host`、`X-Forwarded-For`、`X-Forwarded-Proto`。
2. 把代理地址写进 `app.security.trusted-proxies`，否则代理转发来的请求会被当成明文直连。
3. 打开 `app.security.require-https`，并把会话 Cookie 设为 `Secure`：`JYN_COOKIE_SECURE=true`。
4. 打开「配置管理 → 系统信息」，确认「连接加密」一行显示 **HTTPS 已加密**；显示 **HTTP 明文** 说明代理头没传到或 `trusted-proxies` 没配对。

Nginx 参考配置：

```nginx
server {
    listen 80;
    server_name jync.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name jync.example.com;

    ssl_certificate     /etc/nginx/certs/jync.crt;
    ssl_certificate_key /etc/nginx/certs/jync.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    # 诊断包、批量导入等表单提交
    client_max_body_size 16m;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
}
```

对应环境变量（Docker / K8s）：

```bash
JYN_TRUSTED_PROXIES=127.0.0.1,10.0.0.0/8
JYN_REQUIRE_HTTPS=true
JYN_COOKIE_SECURE=true
```

### 应用内 TLS：在「系统配置」上传证书

不需要反向代理，Jync 自己监听 HTTPS 端口（建议 8443，可与 HTTP 并存）：

1. 准备密钥库。**私钥口令必须与库口令相同**——页面只接受一个口令，两者不同时会以「无法打开密钥库」拒绝：

   ```bash
   # 自签仅供测试；生产换成 CA 签发的证书，并保证 CN / SAN 与访问域名一致
   openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
       -keyout jync.key -out jync.crt -subj "/CN=jync.example.com"
   openssl pkcs12 -export -in jync.crt -inkey jync.key \
       -out jync.p12 -name jync -passout pass:changeit
   ```

   已有的 JKS 可以直接上传 `.jks`，也可以转成 PKCS#12：

   ```bash
   keytool -importkeystore -srckeystore old.jks -srcstoretype JKS \
       -destkeystore jync.p12 -deststoretype PKCS12
   ```

   **也可以不打包**：页面的 PEM 上传方式直接接收 nginx 常用的材料——证书链和私钥可以是两个文件，也可以合在同一个文件里（HAProxy 风格），不需要口令。Jync 在内存里用随机生成的口令把它封装成 PKCS#12 再落盘，私钥不会以明文保存。要求：

   - 证书链按「服务器证书 → 中间证书」顺序排列（certbot 的 `fullchain.pem` 即为该顺序）；
   - 私钥为**无口令**的 PKCS#8（`BEGIN PRIVATE KEY`）或 PKCS#1（`BEGIN RSA PRIVATE KEY`）；带口令的私钥和 `BEGIN EC PRIVATE KEY` 等旧格式会被拒绝，先转换：
     `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-plain.pem`
   - 私钥与证书合并在同一个文件时，把私钥块追加到证书链后面，私钥文件字段留空即可；私钥单独一个文件时，在该字段选择它。
   - Jync 会验证私钥与叶子证书是否配对，不匹配直接拒绝，不会留到下次启动才失败。

2. 以管理员进入「系统管理 → 系统配置 → TLS 证书」。上传方式用卡片里的标签页切换：默认「PEM 证书」，选择证书链和（可选的）私钥；「密钥库」标签页上传 PKCS#12/JKS 并填写口令。填写 HTTPS 端口，按需勾选「重定向 HTTP」，保存。每个文件上限 5 MB；证书已过期或尚未生效会被直接拒绝。
3. **重启 Jync**。连接器在启动时创建，保存只写文件——页面显示「等待重启生效」，重启后才变成「已生效」。
4. 验证：`https://<主机>:8443/login` 能打开；勾选了重定向时，`http://<主机>:8080/login` 返回 307 并指向 HTTPS。

| 键 / 环境变量 | 默认 | 说明 |
|---|---|---|
| `app.tls.enabled` / `JYN_TLS_ENABLED` | `true` | 设为 `false` 时不读取证书、不添加 HTTPS 连接器。用于证书损坏或端口被占导致启动失败时救回实例 |
| `app.tls.directory` / `JYN_TLS_DIRECTORY` | `./data/tls` | 密钥库与 `tls.properties` 边车的目录；口令在边车里用 `sync.crypto-*` 加密。**不在配置包里**，备份数据目录时要一起备份 |
| `app.tls.redirect-host` / `JYN_TLS_REDIRECT_HOST` | 空 | HTTP→HTTPS 重定向使用的固定主机名。留空表示沿用请求的 `Host`（严格校验，非法值返回 400，不转发到外部） |

- **续期**：在新文件上重新导出、再次上传、重启生效；页面在到期前 30 天提示。证书过期后运行中的实例仍会继续用它提供服务（浏览器会报证书过期），删除证书也不会让运行中的实例停止 HTTPS——两种情况都要重新上传或重启。
- **默认密钥告警**：`sync.crypto-*` 仍是随包默认值时，页面会警告边车口令受该密钥保护；应先换成部署专用密钥（见上文密钥说明），再上传证书。
- **多实例**：`data/tls` 是实例本地目录。共享同一张证书时让 `JYN_TLS_DIRECTORY` 指向共享卷，并设置 `JYN_TLS_REDIRECT_HOST`，别让各节点按自己的 `Host` 做重定向。
- **与 `require-https` 的关系**：可以同时开启。已加载证书且上传时勾选了重定向时，**所有**明文请求（包括本机 localhost）一律 307 到 HTTPS；不带 Cookie 的 `GET /login` 健康探针除外。未勾选重定向时由 `require-https` 决定：有证书在跑则回环也返回 403，没有可用证书时回环放行作为应急通道。
- **开启强制 HTTPS 的前提**：页面开关只有在应用内证书已生效、当前访问本身是 HTTPS（反代终止 TLS），两者满足其一时才允许打开。证书尚未重启生效、已过期或根本没有证书时，页面拒绝开启并说明原因——否则没有 HTTPS 入口时，除回环应急通道外的请求都会被 403 锁住。
- **HTTP/2 与 SNI**：内置连接器只提供这张证书，不支持按域名切换证书（多证书 SNI）；需要多个站点证书时用反向代理方式。

常见故障：

| 现象 | 处理 |
|---|---|
| 启动失败，日志有 `ConnectorStartFailedException` / `Address already in use` | HTTPS 端口被占（重复启动的实例，或 HTTP 与 HTTPS 填了同一个端口）。先以 `JYN_TLS_ENABLED=false` 启动，进页面改端口 |
| 启动失败，页面进不去，日志说密钥库不可读 / 摘要不符 / 缺少私钥 | 文件被替换或边车损坏。以 `JYN_TLS_ENABLED=false` 启动一次，在页面上重新上传或删除证书 |
| PEM 上传被拒绝：私钥受口令保护 / 与证书不匹配 | 用 `openssl pkcs8 -topk8 -nocrypt` 去掉私钥口令；并确认私钥与证书来自同一对密钥 |
| 保存成功但 `https://` 打不开 | 还没重启；或防火墙 / 容器端口映射没放行 HTTPS 端口 |
| 浏览器提示证书不受信任 | 自签证书的正常现象；生产用 CA 签发 |

- **直接暴露 8080、又没上传证书的部署不要打开 `require-https`**：没有代理时所有请求都判定为明文，除了本机回环应急通道全部返回 403。用应用内 TLS 时，勾选重定向后**所有**明文请求（含本机 localhost）都会 307 到 HTTPS，不会再落到 403；本机脚本用 `curl` 验证时要加 `-L`，或直接访问 HTTPS 端口。
- **HTTPS 生效时**响应带 `Strict-Transport-Security: max-age=31536000; includeSubDomains`；HSTS 一旦被浏览器记住，回退到 HTTP 会打不开页面，切换前先确认证书长期可用。
- 所有响应带 `Content-Security-Policy`（仅允许同源资源）、`Referrer-Policy: same-origin`、`Permissions-Policy`（关闭定位/麦克风/摄像头），`X-Frame-Options: DENY` 由 Spring Security 默认提供。
- `require-https` 用 403 而不是重定向：它不知道 HTTPS 端口在哪，也不该拿可伪造的 `Host` 去拼跳转目标。应用内 TLS 的重定向（307）只在证书已加载且上传时勾选后生效，主机名只取严格校验过的 `Host` 或 `app.tls.redirect-host`，不采信 `X-Forwarded-Host`，非法值返回 400。反向代理部署的 HTTP→HTTPS 301 仍由上面的 Nginx 完成。
- **反向代理不要开启 `server.forward-headers-strategy=framework`** 之类的全局转发头解析：那会让客户端自带的 `X-Forwarded-For` 改写 `getRemoteAddr()`，绕过登录失败锁定与审计里的真实来源。

## 日志 CDC 前提

六种源库可按任务切换日志 CDC，直接解析源库日志。启动前会逐项探测以下前提：

| 引擎 | 源库前提 | 位点 |
|---|---|---|
| MySQL binlog | 5.7+；`log_bin=ON`、`binlog_format=ROW`、`binlog_row_image=FULL`；账号具备 `REPLICATION SLAVE` 与 `REPLICATION CLIENT`；binlog 保留 ≥ 24h | binlog 文件 + 偏移（含 GTID） |
| Oracle LogMiner | 11g+；`ARCHIVELOG` 模式；库级 `SUPPLEMENTAL LOG DATA`；表级 `SUPPLEMENTAL LOG DATA (ALL) COLUMNS`；挖掘权限；归档保留 ≥ 24h | SCN |
| 达梦 LogMiner | DM8；`ARCH_MODE=Y` 归档模式；`RLOG_APPEND_LOGIC=2`（redo 含完整旧镜像）；`V$ARCHIVED_LOG` 可读且 `DBMS_LOGMNR` 可执行；只能挖已归档文件，归档切换周期就是延迟下限（建议定时 `ALTER SYSTEM SWITCH LOGFILE`） | SCN（`MAX(NEXT_CHANGE#)`） |
| PostgreSQL 逻辑解码 | PostgreSQL 11+ 及 PG 系国产内核（openGauss、GaussDB、Kingbase、Highgo、Vastbase、Oscar、RaseSQL、SeaBoxSQL）；`wal_level=logical`；账号具备 `REPLICATION`（openGauss 不要求该属性）；表 `REPLICA IDENTITY` 非 `NOTHING`；openGauss 建议 `enable_slot_log=on` | LSN |
| SQL Server CDC | 2012+；库级 `sp_cdc_enable_db`；SQL Server Agent 运行中；账号在 `cdc_Admin` / `cdc_reader` | LSN |
| 崖山 YStream | YashanDB 23.4+；`ARCHIVELOG` 且连的是主库；账号具备 `CREATE SESSION`、`SELECT ANY TABLE`、`YSTREAM_CAPTURE`、`SELECT ANY DICTIONARY`；**每张选表**执行 `ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS`；必须显式选表；每个实例最多 32 个 YStream 服务 | 五元组（`commitScn` / 实例号 / `groupLsn` / `groupOffset` / 行号） |

- **崖山（YashanDB）走 YStream，不走 LogMiner**：23.4.7.100 实测没有 `DBMS_LOGMNR`，它自带的逻辑日志引擎是 YStream（`DBMS_YSTREAM_ADM`），由「YStream 实时日志」策略接入，不建触发器、不改表结构。缺补充日志的表 DML 会**静默不进流**，所以探测与启动都会逐表检查并给出可复制的 ALTER 语句。
- **统一流程**：先记位点 → 分块全量基线 → 长驻线程连续回放。达梦与 Oracle 共用同一套 LogMiner 流水线，只有取位点、可挖文件与调用方式走各自的方言适配层。
- **低空闲延迟**：有数据时本轮捞完立即开下一轮；只有真空闲才休眠（LogMiner / SQL Server 默认 1s、PostgreSQL 500ms，最低 50ms）。MySQL binlog 是服务端推送，不做轮询。

## 登录、权限与统一认证

| 用户名 | 角色 | 初始密码 | 权限 |
|---|---|---|---|
| `admin` | 管理员 | `123456` | 全部操作 |
| `view` | 访客 | `123456` | 只读 |

- **权限规则只有一条**：POST 一律要求管理员。访客可查看全部页面与数据，但没有写按钮，构造请求也会被拒。
- **初始账号**：首次启动写入；用户表非空则跳过，不会重置任何人的密码。
- **忘记密码**：无法找回；删除 `app_user` 对应行后重启会重新种入。

**统一认证**（管理员 → 配置管理 → 外部认证）支持三种方式，同一时刻只生效一种：

| 方式 | 适用场景 | 部署要求 |
|---|---|---|
| 账号密码 | 门户提供登录 API | Jync 能访问该 API；支持表单或 JSON 请求与字段映射 |
| 网关头 | 网关 / 反向代理已完成登录并注入身份头 | Jync 必须在网关之后；只信任 `app.security.trusted-proxies` 内的直接对端 |
| 票据 | 登录中心签发一次性 ticket | 配置校验地址；只接受 http(s)，不跟随重定向 |

- 外部用户只保留会话，不写入 `app_user`，没有本地密码，也没有改密入口。
- 本地账号默认保留为应急入口 `/login/local`。关闭前必须先启用并配置好外部认证；关闭后 `POST /login` 也会被拒绝。
- **恢复本地登录**：以 `JYN_RESET_LOGIN=true` 启动一次（等价于 `app.login.reset-on-startup: true`），Jync 会重新打开本地登录并保存；登录后修好外部配置，去掉变量并改回 `false` 重启。
- 嵌入 iframe 需要 HTTPS，并同时设置 `JYN_COOKIE_SECURE=true` 与 `JYN_COOKIE_SAME_SITE=None`。
