# Jync 捷同 · 异构数据库实时同步工具

> **Jync**，读作 "sync" —— **J** 代表 Java；**捷同**：敏捷同步，两库皆同。
> 单 JAR 交付的异构数据库同步引擎：**分块并行的全量、只读零侵入的增量、秒级的日志 CDC**，外加按 SQL 取数的 ETL 与 Webhook 告警。

**简体中文** | [English](README_EN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen.svg)](https://spring.io/projects/spring-boot)

![Jync 捷同 · 异构数据库实时同步工具](docs/images/jync.png)

在**异构数据库**之间持续同步**结构与数据**的开箱即用 Web 应用：单个 jar 启动，浏览器点选即可在内置的 **34 种数据库（另支持自定义类型）** 之间建立同步或 ETL 链路；搜索与文档型数据另有三条不走 JDBC 的**专属迁移**链路（Elasticsearch / OpenSearch → Easysearch、MongoDB → MongoDB、MongoDB → KingbaseES）。其中 Doris、ClickHouse、Hive、Impala、Elasticsearch、MongoDB 六种分析型引擎可作为实时同步的**源**（仅轮询 / 全表比对），也可用于 ETL。不需要 Kafka、Flink、ZooKeeper，也不需要编写一行作业代码。

- **零外部依赖**：Spring Boot 单体 + Thymeleaf 服务端渲染 + Quartz + H2 内嵌元数据库，内网离线可用。
- **全量不是批量查询导入**：主键 keyset 分块、单表内并行、结果集流式读取、逐块提交与检查点，单表吞吐可达 **20 万行/秒量级**。
- **增量只有一条索引查询**：默认轮询模式只读游标之后的变化，一个只读账号即可；六类源库可切换日志 CDC，做到秒级延迟。
- **结构与数据一起走**：目标库缺失时自动建表 / 索引 / 视图 / 存储过程，内置各方言类型映射与函数转换。
- **可靠续传**：停机、断网、进程被杀都能从检查点继续，写入全部幂等，停机期间源库的变更重启后自动补齐。
- **另有专属迁移**：Elasticsearch / OpenSearch → Easysearch、MongoDB → MongoDB、MongoDB → KingbaseES 三条链路不走 JDBC，用独立的连接模型整库复制索引或集合，与实时同步、ETL 互不影响。
- **出了问题会通知你**：同步失败、CDC 停摆、数据漂移等七类故障可推送到钉钉、企业微信、飞书或自定义 Webhook。

| 资源 | 地址 |
|---|---|
| **下载（GitHub）** | [github.com/vfaner/jync/releases/latest](https://github.com/vfaner/jync/releases/latest) |
| **下载（Gitee）** | [gitee.com/super_rgh/jync/releases/latest](https://gitee.com/super_rgh/jync/releases/latest) |
| **镜像（AtomGit）** | [gitcode.com/vfaner/jync](https://gitcode.com/vfaner/jync) |
| **官网** | [jync.qqmu.com](https://jync.qqmu.com) |
| **视频演示** | [Bilibili · 14 分钟，从建连接到实时同步](https://www.bilibili.com/video/BV1iHYJ6vEEd) |
| **文档** | [用户操作手册](https://jync.qqmu.com/docs/jync-user-guide.pdf) · [部署与运维手册](https://jync.qqmu.com/docs/jync-ops-guide.pdf) · [产品介绍](https://jync.qqmu.com/docs/jync-overview.pdf) |

> AtomGit（gitcode.com）是代码与发布包的同步镜像；下载、问题反馈与 PR 以 GitHub / Gitee 为准。

---

## 目录

- [项目背景](#background)
- [核心能力](#features)
- [界面速览](#screenshots)
- [同步引擎：全量、增量与日志 CDC](#engine)
- [性能调优](#performance)
- [调度设置](#schedule)
- [列清洗](#cleansing)
- [支持的数据库](#databases)
- [快速开始](#deploy)
- [常用配置](#config)
- [登录、权限与统一认证](#auth)
- [使用流程](#workflow)
- [AI 辅助转换（可选）](#ai)
- [与市面工具的对比](#comparison)
- [架构与技术栈](#architecture)
- [文档与测试](#docs)
- [参与贡献](#contributing)
- [许可证](#license)

---

<a name="background"></a>
## 项目背景

政企系统从 MySQL、Oracle、SQL Server 迁移到达梦、金仓、OceanBase 等国产数据库时，迁移往往不是一次性搬完：新旧系统双轨运行、分批割接、历史库只读共存，都要求两个库在数周甚至数月里**持续保持一致**。

现成方案总有一步对不上。商业同步工具按节点授权，采购周期长、成本高；Debezium、Flink CDC 能力强，但要先搭 Kafka、Flink 与作业编排；DataX、Kettle 擅长一次性搬运，却不负责长期增量、结构同步与断点续传。

**Jync 把这些逻辑做成一个可交付的软件**：一个 jar，实施人员用浏览器完成建连接、选表、配置调度、启动同步，不必写作业脚本，也不必维护消息队列。MIT 协议开源，可完全私有化部署。

**设计目标**

- **一个 jar**：元数据库、调度器、Web 界面全部内置，内网与离线环境直接运行。
- **分钟级上手**：连接可测通再保存，对象可勾选，配置项都有默认值。
- **结构是一等公民**：建表、索引、视图、存储过程与类型映射由工具负责。
- **国产数据库优先**：达梦、金仓、OceanBase、TiDB、GaussDB 等均有内置方言。
- **可观测**：每轮同步的行数、耗时、吞吐与瓶颈占比都写进日志与页面，故障可推送告警。

---

<a name="features"></a>
## 核心能力

| 分类 | 能力 |
|---|---|
| 全量加载 | 主键 keyset 分块 + 单表内并行 + 结果集流式读取 + 逐块检查点；空表 INSERT-first、延迟二级索引；单表 20 万行/秒量级 |
| 增量同步 | 时间戳 / 行级 SCN / 自增列 / 全表比对自动选策略；一条游标区间查询；水位线安全回退；游标提交后推进 |
| 日志 CDC | MySQL binlog、Oracle LogMiner、达梦 LogMiner、PostgreSQL 系逻辑解码（含 openGauss 等国产内核）、SQL Server CDC、崖山 YStream；长驻线程流式回放，秒级延迟 |
| 结构同步 | 表、索引、视图、存储过程/函数；类型映射、函数转换、标识符引号、分页语法、DDL 覆盖 |
| ETL | 按客户取数 SQL 复制结果行；7 种分析型引擎；多语句脚本；覆盖 / 追加 / 范围覆盖；断点续跑；同源同目标的表可组成**批次**共享连接与调度 |
| 专属迁移 | 三种整库复制：Elasticsearch / OpenSearch → Easysearch（索引自动发现、settings / mappings / alias 复制、按 `_id` / `_routing` 批量写入、兼容性探测）；MongoDB → MongoDB（集合选项与索引复制、文档原样写入并保留 `_id`）；MongoDB → KingbaseES（采样推断建表、`_id` 主键、批量 INSERT / `ON CONFLICT` upsert）。均带预检，手动或 cron 运行，可取消并查看每次运行历史 |
| 数据加工 | 列清洗（7 种规则）、表名映射、只读数据预览、按需行数核对、任务克隆、批量建任务（按表大小分箱） |
| 告警通知 | Webhook 推送同步失败、轮询卡死、项目未运行、CDC 停摆、数据漂移、连接健康、PG slot 积压；通用 JSON / 钉钉 / 企业微信 / 飞书；冷却时间与恢复通知；地址加密存储 |
| 可靠性 | 幂等 upsert、检查点续传、数据库租约锁、at-least-once、停机变更自动补齐 |
| 可观测 | 看板、变更日志、审计日志、系统日志、每表吞吐日志与 CDC 状态面板、按需行数核对、数据库与任务性能曲线（7 天历史）、可选的 Prometheus 指标端点（Bearer token 保护，默认关闭） |
| 交付形态 | jar / Docker / Kubernetes；中英双语、昼夜主题；响应式布局，手机浏览器可用；本地账号与三种统一认证 |

> **ETL 批次：** 一张表仍是一个独立的 ETL 任务，保留各自的取数 SQL、目标表、写模式、断点与运行历史；批次只是把同源同目标的一组表放进同一个调度——运行时只建一对源/目标连接，按顺序执行，单个成员失败不中断后续成员。批次创建后源/目标连接锁定，成员不再单独调度；可从表清单批量勾选生成默认 `select *` 的成员，之后逐个改写 SQL。
>
> **告警只读状态：** 告警评估读取 Jync 已有的运行状态，不改变任何同步、CDC 或 ETL 任务的行为；关闭后不评估规则，也不发送消息。

---

<a name="screenshots"></a>
## 界面速览

<table>
  <tr>
    <td align="center" width="33%"><a href="https://jync.qqmu.com/images/jync_kanban.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_kanban.webp" alt="看板" width="100%"></a><br><sub><b>看板</b> · 资源、任务与 24h 趋势</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_xiangmu.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_xiangmu.webp" alt="实时同步任务" width="100%"></a><br><sub><b>实时同步任务</b> · 状态、上次同步与详情</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_db.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_db.webp" alt="连接管理" width="100%"></a><br><sub><b>连接管理</b> · 源 / 目标分栏</sub></td>
  </tr>
  <tr>
    <td align="center" width="33%"><a href="https://jync.qqmu.com/images/jync_cdc.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_cdc.webp" alt="日志 CDC" width="100%"></a><br><sub><b>日志 CDC</b> · 位点 / 上次事件 / 吞吐 / 跳过计数</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_xiangmu_xn.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_xiangmu_xn.webp" alt="同步任务性能" width="100%"></a><br><sub><b>同步任务性能</b> · 同步行数、延迟、失败与变更构成</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_lqx.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_lqx.webp" alt="列清洗" width="100%"></a><br><sub><b>列清洗</b> · 按表按列配置，写入前生效</sub></td>
  </tr>
  <tr>
    <td align="center" width="33%"><a href="https://jync.qqmu.com/images/jync_warn.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_warn.webp" alt="告警通知" width="100%"></a><br><sub><b>告警通知</b> · Webhook 地址、格式、分类与冷却</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_xiangmu_suoyin_ai.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_xiangmu_suoyin_ai.webp" alt="AI 起草" width="100%"></a><br><sub><b>AI 起草</b> · 候选 SQL 流式进编辑器</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_kanban_anye.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_kanban_anye.webp" alt="暗色主题" width="100%"></a><br><sub><b>暗色主题</b> · 整套配色一起切换</sub></td>
  </tr>
</table>

> 截图托管在官网，正文只保留 9 张；其余界面（ETL、批量创建、审计、系统日志、英文界面等）见[官网功能页](https://jync.qqmu.com/features.html)。

---
<a name="engine"></a>
## 同步引擎：全量、增量与日志 CDC

Jync 有三条数据通路，分别解决"第一次怎么搬""之后怎么追""如何做到秒级"。

```
首次全量：  keyset 分块 → N 个 worker 并行 → 流式读取 → 逐批提交 → 检查点
增量轮询：  cursor > 上次值 AND cursor <= 水位线 → 批量 upsert → 推进游标
日志 CDC：  记录位点 → 分块基线 → 长驻线程连续回放 → 提交后推进位点
```

### 首次全量：分块、并行、流式

全量不是把整表 `SELECT` 出来再批量 `INSERT`，而是一条有界、可中断、可续传的流水线：

```
源库游标（fetch-size 流式取数）
  → 行映射 / 列清洗（内存中逐行）
  → JDBC batch（按行数与字节预算）
  → 目标库提交（每批）
  → 检查点（每块）
```

- **流式读取**：结果集按 `full-load-fetch-size` 增量获取，逐行映射、清洗、绑定，堆内存只占当前批次与当前大字段，不随表大小增长。
- **keyset 分块**：有主键的表按主键游标分页，`chunk-size` 默认 10 万行一块；每块单独提交并写检查点，崩溃最多重读一块。
- **表内并行**：单列整数主键按 `[MIN, MAX]` 切成互不相交的区间；文本、UUID、复合主键按源库键序采样边界；每个 worker 使用独立连接，上限 8。区间不相交，加上写入幂等，完成顺序不影响正确性。
- **无主键表**：目标为空且存在可排序列时，按 OFFSET 分页并以目标行数续传；否则保持单次流式读取。
- **写侧策略**：目标为空时用普通 `INSERT` 代替 upsert；非唯一二级索引延到全量完成后再建；写入期间可临时关闭目标外键。
- **发生时机**：仅首次启动、手动"重置进度"或 CDC"重建基线"时执行；日常轮询只同步增量。

### 增量轮询：只读变化

每张表每轮只有一条查询：

```sql
SELECT ... FROM t WHERE cursor > :last AND cursor <= :watermark
```

- 游标列自动识别：`TIMESTAMP` 识别新增与更新，`ROW_SCN` 识别 Oracle 行级变更，`IDENTITY` 只识别新增。有主键的小表可选用 `FULL_COMPARE` 周期性整表比对；删除只有在开启「同步行删除」后才会处理，更大的表可改用按主键分页的分块比对，或日志 CDC。
- **游标列建议建索引**：轮询靠游标列做增量，没有索引时每轮都要全表扫描；项目详情页会把缺索引的表直接标出来。
- 水位线回退 `safety-lag-ms`：晚提交的事务会在下一轮被重新读到，幂等 upsert 吸收重叠。
- **游标在目标库提交之后才推进**，因此故障恢复语义是 at-least-once，不会丢行。
- 默认的轮询模式不碰源库 binlog，也不建触发器，只需一个只读账号。

### 日志 CDC：秒级流式回放

六种源库可按任务切换日志 CDC，直接解析源库日志，看到 `INSERT / UPDATE / DELETE`：

| 引擎 | 源库前提（探测逐项检查） | 位点 |
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
- **多实例接管**：同一任务的 CDC 流用数据库租约互斥，持锁节点宕机后 ≤ 60s 由其他节点接管续传。
- **一致性契约**：先提交目标库、再复检租约、最后推进位点；崩溃后从上一位点重放，重叠窗口由幂等 upsert 吸收。

### 速度与规模

容器单机基准（50 万行 Oracle 表）中，全量加载实测吞吐为 **21.6 万行/秒**；空表全量采用 INSERT-first 时为 21.1 万行/秒。日志 CDC 端到端延迟为**秒级**，有数据时连续回放、不等待。对于几十张表、百万到千万行的常见信创迁移规模，Jync 的延迟与吞吐处于与 Flink CDC 同一量级；区别在于部署面只有一个 jar。

---

<a name="performance"></a>
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

---

<a name="schedule"></a>
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

---

<a name="cleansing"></a>
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

---

<a name="databases"></a>
## 支持的数据库

内置 **34 种**预设类型，其中二十五种驱动随包内置。下图是官网的数据库能力地图：上半部分是 JDBC 的实时同步 / ETL 矩阵（数据源 → 两种引擎 → 目标库），底部是独立的**专属迁移**链路；点图可看完整说明。

[![数据库能力地图与专属迁移链路](docs/images/jync_dbmaps.webp)](https://jync.qqmu.com/database.html)

- **自定义数据库**：JDBC URL + 驱动类名 + 驱动 jar 路径，运行时用独立 `URLClassLoader` 加载；可指定方言家族（通用 / Oracle / MySQL / PostgreSQL / SQL Server / DB2 / SAP HANA）。
- **驱动内置**：除 **GBase、神通 Oscar、华为 GaussDB、SAP HANA、ClickHouse、Hive、Impala、Elasticsearch、MongoDB（Atlas SQL）** 外均随包内置；这些厂商的构件不可再分发，需自备 jar 并填写路径，驱动类名与预设不同时可在同一张卡片覆盖。
- **协议兼容预设**：万里 GreatDB、银联 UPDRDB、星环 KunDB 兼容 MySQL；平安 RASESQL、东方金信 SeaboxSQL 兼容 PostgreSQL，复用内置驱动与对应方言族。
- **分析型 / 搜索型引擎**：Doris、ClickHouse、Hive、Impala、Elasticsearch、MongoDB 没有日志 CDC，**只作实时同步的源**（轮询 / 全表比对），从不作为目标；ETL 中除 Elasticsearch 与 MongoDB 外都可写入。MongoDB 有 Atlas SQL JDBC 与随包内置的原生驱动两条路径。
- **专属迁移不走 JDBC**：Elasticsearch/OpenSearch → Easysearch、MongoDB → MongoDB、MongoDB → KingbaseES 由独立的「专属连接」与迁移任务完成，不进入上面的能力矩阵；链路见[官网数据库页](https://jync.qqmu.com/database.html#dedicated-migration)，凭据只存加密字段，MongoDB URI 与 Kingbase JDBC URL 禁止写密码，边界见[已知限制](docs/known-limitations.md#11-专属迁移esopensearch--easysearchmongodb--mongodb--kingbasees)。
- GaussDB 与 openGauss 的区别、TDSQL / PolarDB 的分版本、HANA 的 CDC 限制等选型细节，见[官网数据库页](https://jync.qqmu.com/database.html)；驱动版本与许可证见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

---

<a name="deploy"></a>
## 快速开始

### 环境要求

| 项 | 要求 |
|---|---|
| JDK | **17+** |
| 端口 | 默认 `8080` |
| 内存 / 磁盘 | 建议堆 ≥ 512MB，预留 1GB；大字段同步按最大单值放大堆 |
| 工作目录 | 必须固定：`./data`（元数据库与密钥）与 `./logs` 相对进程工作目录生成 |

首次启动会在工作目录创建 `./data` 与 `./logs`。**不要在不同目录反复启动**，否则会连到另一个空的元数据库，看起来像"配置都不见了"。

### 获取并启动

- GitHub：<https://github.com/vfaner/jync/releases/latest>
- Gitee：<https://gitee.com/super_rgh/jync/releases/latest>
- 源码编译：`mvn clean package -DskipTests`，产物 `target/jync.jar`

```bash
mkdir -p /opt/jync && cd /opt/jync
java -Xms512m -Xmx2g -Duser.timezone=Asia/Shanghai \
  -jar jync.jar --spring.config.additional-location=file:./application.yml
```

> ⚠️ 必须使用 `--spring.config.additional-location`（叠加覆盖）。替换式的 `spring.config.location` 会丢掉 jar 内置配置，症状是登录页显示 `??login.title_zh_CN??`、全新部署不建表。
>
> 🕐 `-Duser.timezone` 同时决定 Cron 的求值时区；服务器默认是 UTC 时务必显式指定。

最小 `application.yml`：

```yaml
server:
  port: 8080
sync:
  poll-interval: 2000
  full-load-parallelism: 2
  max-pool-size: 5
  crypto-password: ""   # 留空自动生成，保存到 data/<库名>.key
  crypto-salt: ""
```

### 首次登录

访问 `http://<服务器>:8080`：

| 用户名 | 角色 | 初始密码 |
|---|---|---|
| `admin` | 管理员 | `123456` |
| `view` | 访客 | `123456` |

**首次登录后必须修改密码**：未修改前只能停留在改密页；密码使用 BCrypt 存储。

### 部署到生产

生产配置、外部元数据库、systemd、Docker / Kubernetes、反向代理与 HTTPS、升级备份与内网离线部署的完整步骤见：

- [部署与运维手册（PDF）](https://jync.qqmu.com/docs/jync-ops-guide.pdf)
- 官网[部署页](https://jync.qqmu.com/deploy.html)
- 仓库内[容器部署说明](docs/deploy-container.md)

> 🔐 升级前备份 `./data`（连同 `<库名>.key`）、`./logs` 与 `application.yml`。密钥丢失后，已保存的连接密码、AI 密钥与告警地址将无法解密，只能重新填写。

---

<a name="config"></a>
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

---

<a name="auth"></a>
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

---

<a name="workflow"></a>
## 使用流程

1. **数据库连接** → 新建源 / 目标连接 → "测试连接"确认可用。
2. **实时同步 → 新建同步任务**，选定源库与目标库。
3. **任务详情** → 勾选表 / 视图 / 存储过程；需要改目标表名时直接在右侧填写（留空即同名）。
4. 按需配置**捕获策略、调度、全量加载性能与列清洗**。
5. "**立即同步**"验证一次，或"**开启自动同步**"进入持续增量。
6. 在"**变更日志**"查看每轮明细，在"**看板**"查看整体状态；需要确认两边数据是否一致时，在任务详情点「**核对行数**」。

**ETL**：**同步任务 → ETL 任务**新建任务或批次 → 填写取数 SQL 与目标表 → 选择写模式与调度 → 启动。同源同目标的多个表建议建一个批次，共享连接与调度。

**专属迁移**：**同步任务 → 专属迁移** → 管理连接 → 选择迁移类型 → 勾选对象、目标命名与写入方式 → 先预检 → 保存后「立即运行」或启用 cron。三种类型都只复制运行那一刻的数据，不是持续同步；重复运行 OVERWRITE 可让目标收敛。

- **ES / OpenSearch → Easysearch**：源与目标都是搜索连接，勾选索引，复制 settings / mappings / alias 与文档。
- **MongoDB → MongoDB**：源与目标都是 MongoDB 连接（`databaseName` 指定库），勾选集合，复制集合选项、索引与文档，`_id` 不变。同一集群跨库复制需要建两条连接记录——任务要求源连接与目标连接不是同一条。
- **MongoDB → KingbaseES**：源是 MongoDB、目标是 KingbaseES 连接（填 schema，留空用当前 schema），勾选集合；按采样推断的列建表，`_id` 是主键，`upsertOnId` 打开后重复 `_id` 会被覆盖。

**告警**：**配置管理 → 告警通知** → 填写 Webhook 地址、选择消息格式与分类、设置重复提醒间隔 → 保存后可发一条测试消息。

> 首次全量时若目标表已有部分相同数据，条目会写成 `50 row(s) inserted (53 scanned, 3 already up to date)`——扫描多少、真正写入多少、多少行已是最新一目了然。

---

<a name="ai"></a>
## AI 辅助转换（可选）

存储过程的过程化控制流无法靠文本替换可靠翻译。`AI 配置` 提供另一条路：模型**起草候选**，人工审阅后落库。

- **四种协议**：OpenAI 兼容、Anthropic、Ollama（OpenAI 兼容端点）、Ollama（原生 `/api/chat`）。
- **硬边界**：AI 只产候选，**永不参与同步**；候选经人工确认后才写入 DDL 覆盖；未启用供应商时不产生任何外部请求。
- **真实探测**：保存前发送一次 `max_tokens: 1` 的真实请求，而不是只探 TCP。
- **审阅页**：左右对照源定义与机械转换结果；模型自述的"不确定项"比 SQL 本身更值得先读。
- **深度思考档位**：可跟随模型、关闭、低、高；Anthropic 按协议发 `thinking.budget_tokens`，OpenAI 形状发 `reasoning_effort`。

> ⚠️ 转换会把存储过程源码发往你配置的端点。内网或合规部署请接本地推理端点（本机装了 Ollama 可直接选本地预设）。

---

<a name="comparison"></a>
## 与市面工具的对比

> 口径：截至 2026-10，依据各工具官方文档与开源版默认部署形态，仅比较与本工具定位重叠的"开箱即用的异构库持续同步"能力。各工具在自身定位上更强，本表不构成对其整体评价。更完整的分维度对比见 <https://jync.qqmu.com/compare.html>。

| 维度 | **Jync** | Debezium + Kafka | DataX | Kettle | Navicat / DBeaver 传输 |
|---|---|---|---|---|---|
| 部署形态 | **单个 jar** | Kafka + Connect + ZK | 客户端脚本 | 桌面 + 资源库 | 桌面客户端 |
| 配置方式 | **Web 点选** | YAML / REST + 消费端代码 | JSON 作业 | 图形 ETL | 向导 |
| 上手速度 | **分钟级** | 天级：先搭 Kafka 全家桶 | 小时级：手写 JSON 作业 | 天级：理解转换与作业模型 | 分钟级，但仅一次性迁移 |
| 持续增量 | 轮询 / Cron + 日志 CDC（MySQL、Oracle、达梦、PostgreSQL、SQL Server、崖山）；6 种分析型引擎可作轮询源 | 日志级 | 不适用（一次性） | 部分（自建定时） | 部分（计划任务 / 全表比对） |
| 全量加载 | **keyset 分块 + 表内并行 + 流式读取 + 检查点** | 支持快照；异构目标需自写消费端 | 分片作业 | 作业内实现 | 一次性向导 |
| 结构同步 | **自动建表 / 索引 / 视图 / 过程** | 部分，落库需自写 | 需预建表 | 部分 | 仅一次性 |
| ETL 与批次 | **取数 SQL + 7 种分析型引擎 + 批次共享连接与调度** | 需外接 | 分片作业 | 图形转换 | 仅导入导出 |
| 国产数据库 | **达梦 / 金仓 / OceanBase 等 10 种以上一等公民** | 部分 | 部分 | 部分 | 部分 |
| 源库侵入 | **默认只读零侵入**；CDC 需复制权限但仍只读 | 需开 binlog / WAL | 只读 | 只读 | 只读 |
| 断点续传 / 幂等 | **游标持久化 + 主键 upsert + 检查点** | offset | 不适用 | 部分 | 不适用 |
| 告警与可观测 | **七类故障 Webhook 告警、看板、变更 / 审计 / 系统日志、Prometheus 指标端点** | 依赖外部监控栈 | 无 | 日志 | 客户端通知 |
| 运维成本 | **一个进程** | 流式平台 + 消费端 | 需外部调度 | 资源库 + 调度 | 客户端计划任务 / 人工 |

**主要差异**

1. **一个 jar 对一套流式基础设施**：规模配不上运维成本时，差距是决定性的。
2. **分钟级上手**：实施人员按向导配置即可，不需要团队先掌握流式平台。
3. **结构与数据一起同步**：建表、类型映射、索引与过程转换不再是你自己的作业。
4. **为国产库与信创迁移而生**：方言有专门实现，而不是"通用 JDBC 也许能连"。
5. **全量也为持续同步设计**：分块、并行、流式与检查点，让第一次装载同样是可恢复、可观测的工程流程。

---

<a name="architecture"></a>
## 架构与技术栈

**技术栈**：Java 17 · Spring Boot 2.7 · Spring Data JPA / Hibernate · Quartz · H2 / MySQL / PostgreSQL 元数据库 · Thymeleaf 服务端渲染 · 原生 JavaScript（无 CDN、无前端构建）。

```text
com.qqmu.jync
├── config           i18n、Quartz、Jackson、SecurityConfig、SyncProperties
├── controller       MVC；controller/api 为 REST 端点
├── service
│   ├── connection   DataSourceManager、DriverLoader、连接测试
│   ├── metadata     各方言元数据读取 + 快照
│   ├── monitor      ChangeDetector、CursorStrategyResolver
│   ├── converter    SqlDialect、类型映射、SQL 体转换
│   ├── sync         SyncEngine、DataSyncService、StructureSyncService、DdlExecutor
│   ├── cdc          binlog / LogMiner / PG / SQL Server / YStream 消费者
│   ├── etl          取数、写模式、检查点、批次
│   ├── migration    专属迁移：连接适配器、ES/OpenSearch → Easysearch、MongoDB → MongoDB / KingbaseES
│   ├── alert        故障采集、评估、Webhook 推送
│   ├── task         Quartz 调度、三层锁、上下文装配、启动恢复
│   ├── lob          大字段流式读取、绑定与落盘
│   └── ai           供应商配置、共享 HTTP 层、候选起草与审阅
├── model / repository / dto / util
```

- **三层锁**：Quartz `@DisallowConcurrentExecution` → JVM 内按任务 `ReentrantLock` → 数据库租约锁（跨重启与跨实例）。
- **事务语义**：游标推进与锁获取使用 `REQUIRES_NEW`，避免与数据写入同事务回滚。
- **调度对账**：多实例部署时，每个节点把本地 Quartz 调度与共享库中的启用标志对齐。
- **告警状态**：故障的起止状态存数据库，多节点与故障切换共用同一个时钟。
- **链路隔离**：实时同步与 ETL 走 JDBC 连接与任务；专属迁移有自己的连接模型（搜索 / MongoDB / KingbaseES 适配器按 kind 校验端点与凭据）和运行记录，不占用 JDBC 连接池，也不进入 JDBC 能力矩阵。

---

<a name="docs"></a>
## 文档与测试

- **容器部署与运维**：[docs/deploy-container.md](docs/deploy-container.md)（环境变量、TLS、多实例、升级与排障）
- **已知限制与边界**：[docs/known-limitations.md](docs/known-limitations.md)（CDC 边界、无主键表、大字段、过程转换）
- **Oracle → 达梦迁移实战**：[docs/migration-harness-oracle-to-dm.md](docs/migration-harness-oracle-to-dm.md)
- **项目文档（PDF）**：[产品介绍](https://jync.qqmu.com/docs/jync-overview.pdf) · [用户操作手册](https://jync.qqmu.com/docs/jync-user-guide.pdf) · [部署与运维手册](https://jync.qqmu.com/docs/jync-ops-guide.pdf)
- **版本记录**：[GitHub Releases](https://github.com/vfaner/jync/releases) · [Gitee Releases](https://gitee.com/super_rgh/jync/releases) · [AtomGit 镜像](https://gitcode.com/vfaner/jync/releases)
- **第三方驱动许可证**：[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)

```bash
mvn test
```

**4439 个测试：4438 通过，1 个仅限 Linux 的密钥文件权限用例在 macOS 上按环境跳过。**

重点覆盖：

- **方言与 SQL**：各方言幂等 upsert、类型上限、SQL 正文改写不碰字面量与注释。
- **全量与续传**：keyset 分块、并行区间不相交、无主键 OFFSET 续传、检查点、列清洗与主键边界。
- **安全**：密码、AI 密钥与告警地址加密、访客 POST 全拒、CSRF 缺失即拒、外部认证模式隔离。
- **CDC**：六类引擎的前提探测、位点纪律、租约互斥、故障摘要。
- **ETL 与告警**：批次共享连接与顺序执行、写模式与断点、告警分类、冷却与恢复通知。
- **页面与国际化**：全条件分支渲染无 `??`、英文界面无中文泄漏、中英 bundle 键集一致。
- **升级**：GitHub → Gitee 双通道兜底、下载校验、替换备份与回滚。

真实库集成测试（`*IT`，默认不进套件，需 Docker）：Oracle 23ai 并行全量与 `ROWDEPENDENCIES`；MySQL 8 binlog 的基线 → 流式、崩溃重跑、十万行一致、purge 与断线故障。运行加 `-Dapi.version=1.44`（Docker Engine ≥ 29）。

---

<a name="contributing"></a>
## 参与贡献

| 渠道 | 账号 |
|------|------|
| QQ | [817094](https://wpa.qq.com/msgrd?v=3&uin=817094&site=qq&menu=yes) |
| QQ | [2912167928](https://wpa.qq.com/msgrd?v=3&uin=2912167928&site=qq&menu=yes) |
| QQ 群 | [426669837 · 沐沐开源工坊](https://qun.qq.com/universal-share/share?ac=1&authKey=xm%2BfgdraFGDpH8GHkV4DbuMjArg6BsPdNnXKUqcWvFXI1ocS56TcioSRYOVBvn5Q&busi_data=eyJncm91cENvZGUiOiI0MjY2Njk4MzciLCJ0b2tlbiI6Ind0V1pMYlVieXJkQmtnMVgveUFsOHFnbFhTd28wUE03dy9uVUErVExBdnlkMENnMis0UmhqWlJjcURzRHFnUEMiLCJ1aW4iOiI4MTcwOTQifQ%3D%3D&data=tZVIfZoO6N9QWSJE6ZbdZR15OmuLpSVucid5TicWYaMgb9Go1B975NZk0iZOo69AEzXQ4XucFtenImLltzFI5w&svctype=4&tempid=h5_group_info) |
| 微信 | qqmu66 |
| 邮箱 | [vfaner@88.com](mailto:vfaner@88.com) |
| 官网 | <https://jync.qqmu.com> |

问题反馈、功能建议与 PR 欢迎通过以上任一渠道联系。如果 Jync 对你有帮助，欢迎点一个 **Star** ⭐。

---

<a name="license"></a>
## 许可证

[MIT License](LICENSE)，商业与非商业用途均可。内置第三方 JDBC 驱动各自适用原厂许可证，见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)；GBase、神通与华为 GaussDB 驱动不随本项目分发。

项目由沐沐开源工坊维护：GitHub [@vfaner](https://github.com/vfaner)、Gitee [@super_rgh](https://gitee.com/super_rgh)、AtomGit [@vfaner](https://gitcode.com/vfaner)。代码可自由使用、修改与再分发（MIT）；唯一的请求是不要把「Jync / 捷同」名称、logo 与作者署名替换成自己的，详见 [TRADEMARK.md](TRADEMARK.md)。
