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

- 规则按"表 + 列"配置，表名与列名**大小写不敏感**；同一张表内按保存顺序依次执行（先 `TRIM` 再 `BLANK_TO_NULL`，纯空白才会变成 `NULL`）。
- **只处理字符串值**，数字与日期原样通过；全量、增量轮询与 CDC 回放走同一套规则。
- 被清洗的列会强制整列物化读取，单值超过 `sync.lob.force-inline-max-bytes`（默认 1MB）会明确报错并指出表、列与配置键。
- `STRIP_PREFIX` / `STRIP_SUFFIX` / `REGEX_REPLACE` 必须填写参数；正则按 Java 语义在保存时校验，非法表达式与替换串直接拒绝。

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
