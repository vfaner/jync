# Jync 捷同 · 异构数据库实时同步工具

> **Jync**，读作 "sync" —— **J** 代表 Java；**捷同**：敏捷同步，两库皆同。
> 单 JAR 交付的异构数据库实时同步引擎：**分块并行的全量、只读零侵入的增量、秒级的日志 CDC**。

**简体中文** | [English](README_EN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen.svg)](https://spring.io/projects/spring-boot)

![Jync 捷同 · 异构数据库实时同步工具](docs/images/jync.png)

在**异构数据库**之间持续同步**结构与数据**的开箱即用 Web 应用：单个 jar 启动，浏览器点选即可在内置的 **26 种数据库（另支持自定义类型）之间任意两种**建立同步链路。不需要 Kafka、Flink、ZooKeeper，也不需要编写一行作业代码。

- **零外部依赖**：Spring Boot 单体 + Thymeleaf 服务端渲染 + Quartz + H2 内嵌元数据库，内网离线可用。
- **全量不是批量查询导入**：主键 keyset 分块、单表内并行、结果集流式读取、逐块提交与检查点，单表吞吐可达 **20 万行/秒量级**。
- **增量只有一条索引查询**：默认轮询模式只读游标之后的变化，一个只读账号即可；四类源库可切换日志 CDC，做到秒级延迟。
- **结构与数据一起走**：目标库缺失时自动建表 / 索引 / 视图 / 存储过程，内置各方言类型映射与函数转换。
- **可靠续传**：停机、断网、进程被杀都能从检查点继续，写入全部幂等，停机期间源库的变更重启后自动补齐。

| 资源 | 地址 |
|---|---|
| **下载（GitHub）** | [github.com/vfaner/jync/releases/latest](https://github.com/vfaner/jync/releases/latest) |
| **下载（Gitee）** | [gitee.com/super_rgh/jync/releases/latest](https://gitee.com/super_rgh/jync/releases/latest) |
| **镜像（AtomGit）** | [gitcode.com/vfaner/jync](https://gitcode.com/vfaner/jync) |
| **官网** | [jync.qqmu.com](https://jync.qqmu.com) |
| **视频演示** | [Bilibili · 26 分钟，从建连接到实时同步](https://www.bilibili.com/video/BV1iHYJ6vEEd) |

> AtomGit（gitcode.com）是代码与发布包的同步镜像；下载、问题反馈与 PR 以 GitHub / Gitee 为准。

---

## 目录

- [项目背景](#background)
- [核心能力](#features)
- [界面速览](#screenshots)
- [同步引擎：全量、增量与日志 CDC](#engine)
- [全量加载性能与调优](#performance)
- [调度设置](#schedule)
- [列清洗](#cleansing)
- [支持的数据库](#databases)
- [部署](#deploy)
- [配置项](#config)
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

**从真实的信创迁移现场长出来。** 政企系统从 MySQL、Oracle、SQL Server 迁移到达梦、人大金仓、OceanBase 等国产数据库时，迁移往往不是"一次性搬完就结束"：新旧系统双轨运行、分批割接、历史库只读共存，都要求两个库在数周甚至数月里**持续保持一致**。

**现成方案总有一步对不上。** 商业同步工具按节点或按库授权，采购周期长、成本高；Debezium、Flink CDC 这类日志级方案能力强，但要先搭 Kafka、Flink 与作业编排，实施和运维门槛不低；DataX、Kettle 擅长一次性搬运与 ETL，却不负责长期增量、结构同步和断点续传。

**于是把同步做成一个可交付的软件。** Jync 把迁移现场反复使用的同步逻辑沉淀为单体 Web 应用：一个 jar 交付，实施人员用浏览器完成建连接、选表、配置调度、启动同步，不必写作业脚本，也不必维护消息队列。项目以 MIT 协议开源，代码可审计，可完全私有化部署。

**设计目标**

- **一个 jar**：元数据库、调度器、Web 界面全部内置，内网与离线环境直接运行。
- **分钟级上手**：连接可测通再保存，对象可勾选，配置项都有默认值。
- **结构是一等公民**：建表、索引、视图、存储过程与类型映射由工具负责，而不是留给使用者的作业脚本。
- **国产数据库优先**：达梦、金仓、OceanBase、TiDB、GaussDB 等均有内置方言，而不是"通用 JDBC 也许能连"。
- **可观测**：每轮同步的行数、耗时、吞吐与瓶颈占比都写进日志与页面。

---

<a name="features"></a>
## 核心能力

| 分类 | 能力 |
|---|---|
| 全量加载 | 主键 keyset 分块 + 单表内并行 + 结果集流式读取 + 逐块检查点；空表 INSERT-first、延迟二级索引；单表 20 万行/秒量级 |
| 增量同步 | 时间戳 / 行级 SCN / 自增列 / 全表比对自动选策略；一条游标区间查询；水位线安全回退；游标提交后推进 |
| 日志 CDC | MySQL binlog、Oracle LogMiner、PostgreSQL 逻辑解码、SQL Server CDC；长驻线程流式回放，秒级延迟 |
| 结构同步 | 表、索引、视图、存储过程/函数；类型映射、函数转换、标识符引号、分页语法、DDL 覆盖 |
| 数据加工 | 列清洗（7 种规则）、表名映射、只读数据预览、按需行数核对、任务克隆 |
| 可靠性 | 幂等 upsert、检查点续传、数据库租约锁、at-least-once、停机变更自动补齐 |
| 可观测 | 看板、变更日志、审计日志、系统日志、每表吞吐日志与 CDC 状态面板、按需行数核对 |
| 交付形态 | jar / Docker / Kubernetes；中英双语、昼夜主题；响应式布局，手机浏览器可用；本地账号与三种统一认证 |

---

<a name="screenshots"></a>
## 界面速览

<table>
  <tr>
    <td align="center" width="33%"><a href="docs/images/jync_kanban.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban.png" alt="看板" width="100%"></a><br><sub><b>看板</b> · 连接 / 任务 / 表数与 24h 变更</sub></td>
    <td align="center" width="33%"><a href="docs/images/jync_xiangmu.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu.png" alt="同步任务" width="100%"></a><br><sub><b>同步任务</b> · 多任务并行启停</sub></td>
    <td align="center" width="33%"><a href="docs/images/jync_db.png" target="_blank" rel="noopener"><img src="docs/images/jync_db.png" alt="连接管理" width="100%"></a><br><sub><b>连接管理</b> · 源 / 目标分栏</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_db_add.png" target="_blank" rel="noopener"><img src="docs/images/jync_db_add.png" alt="新增连接" width="100%"></a><br><sub><b>新增连接</b> · 26 种预置类型，测通再保存</sub></td>
    <td align="center"><a href="docs/images/jync_celue.png" target="_blank" rel="noopener"><img src="docs/images/jync_celue.png" alt="捕获策略" width="100%"></a><br><sub><b>捕获策略</b> · 轮询 / 全量比对 / 日志 CDC</sub></td>
    <td align="center"><a href="docs/images/jync_lx.png" target="_blank" rel="noopener"><img src="docs/images/jync_lx.png" alt="任务详情" width="100%"></a><br><sub><b>任务详情</b> · 对象、调度、性能、列清洗</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_cdc.png" target="_blank" rel="noopener"><img src="docs/images/jync_cdc.png" alt="日志 CDC" width="100%"></a><br><sub><b>日志 CDC</b> · 位点 / 落后秒数 / 吞吐 / 跳过计数</sub></td>
    <td align="center"><a href="docs/images/jync_diaodu.png" target="_blank" rel="noopener"><img src="docs/images/jync_diaodu.png" alt="调度设置" width="100%"></a><br><sub><b>调度设置</b> · 轮询间隔或 Cron</sub></td>
    <td align="center"><a href="docs/images/jync_xn.png" target="_blank" rel="noopener"><img src="docs/images/jync_xn.png" alt="全量加载性能" width="100%"></a><br><sub><b>全量加载性能</b> · 批量大小与并行度</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_lqx.png" target="_blank" rel="noopener"><img src="docs/images/jync_lqx.png" alt="列清洗" width="100%"></a><br><sub><b>列清洗</b> · 按表按列配置，写入前生效</sub></td>
    <td align="center"><a href="docs/images/jync_data_yulan.png" target="_blank" rel="noopener"><img src="docs/images/jync_data_yulan.png" alt="数据预览" width="100%"></a><br><sub><b>数据预览</b> · 源 / 目标前 100 行</sub></td>
    <td align="center"><a href="docs/images/jync_log.png" target="_blank" rel="noopener"><img src="docs/images/jync_log.png" alt="变更日志" width="100%"></a><br><sub><b>变更日志</b> · 对象 / 类型 / 行数 / 耗时</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_shenji.png" target="_blank" rel="noopener"><img src="docs/images/jync_shenji.png" alt="审计日志" width="100%"></a><br><sub><b>审计日志</b> · 登录与操作留痕</sub></td>
    <td align="center"><a href="docs/images/jync_sys_log.png" target="_blank" rel="noopener"><img src="docs/images/jync_sys_log.png" alt="系统日志" width="100%"></a><br><sub><b>系统日志</b> · 文件 / 级别 / 关键字检索</sub></td>
    <td align="center"><a href="docs/images/jync_renzheng.png" target="_blank" rel="noopener"><img src="docs/images/jync_renzheng.png" alt="外部认证" width="100%"></a><br><sub><b>外部认证</b> · 账号密码 / 网关头 / 票据</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_ai.png" target="_blank" rel="noopener"><img src="docs/images/jync_ai.png" alt="AI 配置" width="100%"></a><br><sub><b>AI 配置</b> · 多供应商，同时只启用一个</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_suoyin_ai.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_suoyin_ai.png" alt="AI 起草" width="100%"></a><br><sub><b>AI 起草</b> · 候选 SQL 流式进编辑器</sub></td>
    <td align="center"><a href="docs/images/jync_system.png" target="_blank" rel="noopener"><img src="docs/images/jync_system.png" alt="系统信息" width="100%"></a><br><sub><b>系统信息</b> · 运行参数与版本</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_kanban_anye.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban_anye.png" alt="暗色主题" width="100%"></a><br><sub><b>暗色主题</b> · 整套配色一起切换</sub></td>
    <td align="center"><a href="docs/images/jync_kanban_en.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban_en.png" alt="英文界面" width="100%"></a><br><sub><b>英文界面</b> · 中 / EN 一键切换</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_add.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_add.png" alt="新建任务" width="100%"></a><br><sub><b>新建任务</b> · 源与目标各选一条已测通连接</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_xiangmu_batch.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_batch.png" alt="批量创建" width="100%"></a><br><sub><b>批量创建</b> · 按表大小分箱，一次建多个任务</sub></td>
    <td align="center"><a href="docs/images/jync_check.png" target="_blank" rel="noopener"><img src="docs/images/jync_check.png" alt="核对行数" width="100%"></a><br><sub><b>核对行数</b> · 源 / 目标 COUNT 对比与差异标记</sub></td>
    <td></td>
  </tr>
</table>

> 截图为高分辨率原图，点击会在新标签页打开原图；Gitee 上还会叠加内置的图片放大预览。
>
> 「核对行数」只在你确认后执行：逐表串行查询源 / 目标 `COUNT(*)`，单侧 15 秒超时；缺表、超时与其他异常分别标记，一侧失败不影响另一侧结果。

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
- **keyset 分块**：有主键的表按主键游标分页，`chunk-size` 默认 10 万行一块；每块单独提交并写检查点，崩溃最多重读一块，而不是整表重来。
- **表内并行**：单列整数主键按 `[MIN, MAX]` 算术切成互不相交的区间；文本、UUID、复合主键按源库键序采样边界；每个 worker 使用独立连接，上限 8。区间不相交，加上写入幂等，完成顺序不影响正确性。
- **无主键表**：目标为空且存在可排序列时，按 OFFSET 分页并以目标行数续传；否则保持单次流式读取。
- **写侧优化**：目标为空时用普通 `INSERT` 代替 upsert；非唯一二级索引延到全量完成后再建；写入期间可临时关闭目标外键。
- **发生时机**：仅首次启动、手动"重置进度"或 CDC"重建基线"时执行；日常轮询只同步增量。

### 增量轮询：只读变化

每张表每轮只有一条查询：

```sql
SELECT ... FROM t WHERE cursor > :last AND cursor <= :watermark
```

- 游标列自动识别：`TIMESTAMP` 识别新增与更新，`ROW_SCN` 识别 Oracle 行级变更，`IDENTITY` 只识别新增。有主键的小表可选用 `FULL_COMPARE` 周期性整表比对：自动选择只覆盖行数不超过 `sync.full-compare-max-rows`（默认 20000）的表，手动在任务级选择则不受该上限约束，但每个轮询周期都会整表读取源库；删除只有在开启「同步行删除」且表行数不超过上限时才会处理。详情页与启动日志会列出超限、或按当前轮询间隔负担过重的表，并给出建议间隔。
- 水位线回退 `safety-lag-ms`：晚提交的事务会在下一轮被重新读到，幂等 upsert 吸收重叠。
- **游标在目标库提交之后才推进**，因此故障恢复语义是 at-least-once，不会丢行。
- 默认的轮询模式不碰源库 binlog，也不建触发器，只需一个只读账号。

### 日志 CDC：秒级流式回放

四种源库可按任务切换日志 CDC，直接解析源库日志，看到 `INSERT / UPDATE / DELETE`：

| 引擎 | 源库前提（探测逐项检查） | 位点 |
|---|---|---|
| MySQL binlog | 5.7+；`log_bin=ON`、`binlog_format=ROW`、`binlog_row_image=FULL`；账号具备 `REPLICATION SLAVE` 与 `REPLICATION CLIENT`；binlog 保留 ≥ 24h | binlog 文件 + 偏移（含 GTID） |
| Oracle LogMiner | 11g+；`ARCHIVELOG` 模式；库级 `SUPPLEMENTAL LOG DATA`；表级 `SUPPLEMENTAL LOG DATA (ALL) COLUMNS`；挖掘权限；归档保留 ≥ 24h | SCN |
| PostgreSQL 逻辑解码 | 9.4+；`wal_level=logical`；账号具备 `REPLICATION`；表 `REPLICA IDENTITY` 非 `NOTHING` | LSN |
| SQL Server CDC | 2012+；库级 `sp_cdc_enable_db`；SQL Server Agent 运行中；账号在 `cdc_Admin` / `cdc_reader` | LSN |

- **统一流程**：先记位点 → 分块全量基线 → 长驻线程连续回放。位点在基线之前写入，所以基线期间的变更不会漏。
- **低空闲延迟**：有数据时本轮捞完立即开下一轮，0 额外等待；只有真空闲才休眠（LogMiner / SQL Server 默认 1s、PostgreSQL 500ms，最低 50ms）。MySQL binlog 是服务端推送，不做轮询。
- **多实例接管**：同一任务的 CDC 流用数据库租约互斥，持锁节点宕机后 ≤ 60s 由其他节点接管续传。
- **一致性契约**：先提交目标库、再复检租约、最后推进位点；崩溃后从上一位点重放，重叠窗口由幂等 upsert 吸收。

### 速度与规模

在容器单机基准（50 万行 Oracle 表）中，全量加载的实测吞吐为 **21.6 万行/秒**；开启 INSERT-first 后，空表全量从 MERGE 的 8.4 万行/秒提升到 21.1 万行/秒。日志 CDC 端到端延迟为**秒级**，有数据时连续回放、不等待。对于几十张表、百万到千万行的常见信创迁移规模，Jync 的延迟与吞吐处于与 Flink CDC 同一量级；区别在于部署面只有一个 jar，不需要 Kafka、Flink 集群与作业编排。

---

<a name="performance"></a>
## 全量加载性能与调优

全量只发生一次，但往往决定整个迁移窗口的长度。Jync 为每张表、每个并行 worker 打印一行吞吐日志，把"慢"拆成可操作的三个占比。

### 吞吐日志怎么看

```text
table=orders thread=full-load-orders rows=500000 changed=500000 elapsed=2.3s rows/s=216000.0 read=38% write=52% commit=10% batches=250 rowsPerBatch=2000
```

| 字段 | 含义 | 怎么读 |
|---|---|---|
| `table` / `thread` | 表名与执行线程 | 并行时每个 worker 各打一行，线程名形如 `full-load-orders` |
| `rows` / `changed` | 读取行数 / 实际写入行数 | 两者不同说明本轮是重读比对；`changed=0` 的轮次不会打 INFO |
| `elapsed` / `rows/s` | 耗时与吞吐 | 跨轮次、跨调参对比的主指标 |
| `read%` | 源库取数 + 行映射占比 | 偏高说明源库游标、网络或映射是瓶颈 |
| `write%` | 清洗 + 绑定 + `addBatch` + `executeBatch` 占比（不含提交） | 偏高说明目标库写入、约束或大字段绑定是瓶颈 |
| `commit%` | 目标库 `COMMIT` 等待占比 | 偏高说明 redo、备库同步或磁盘延迟是瓶颈 |
| `batches` / `rowsPerBatch` | 批次数 / 每批实际行数 | `rowsPerBatch` 接近批量大小才说明驱动真的在批处理 |

`read% + write% + commit%` 合计 100%。只有 `rows >= 1000` 且 `changed > 0` 的轮次打 INFO，小窗口与无变化的轮次降到 DEBUG，避免刷屏。

### 先判断瓶颈，再调参数

| 现象 | 通常原因 | 优先处理 |
|---|---|---|
| `commit%` 是三者中最大 | 目标库 redo 慢、磁盘慢、Data Guard 同步传输、事务过大 | 批量 2000 → 5000 减少提交次数；确认存储与备库；保持延迟建索引 |
| `write%` 最大 | 目标库绑定 / 网络往返 / 外键与索引维护 / 大字段 | 批量 2000 → 5000；写入期间关闭目标外键；检查大字段绑定与索引 |
| `read%` 最大 | 源库全表或大范围扫描、游标无索引、源库繁忙 | 确认游标列有索引；fetch 提到 2000–5000（MySQL 源不生效）；并行度 1 → 2 → 4；错峰执行 |
| `rowsPerBatch` 长期不足批量的一半 | 驱动没有真正批处理，或大字段被单行提交 | 检查驱动版本与 LOB 配置；确认批次预算未被单值占满 |
| 三项都不高但 `rows/s` 低 | 网络 RTT 高、批次太小、单线程 | 批量不低于 1000；并行度 1 → 2（基准约 +57%）；检查链路延迟 |

### 参数怎么调

| 参数 | 默认 | 可取值范围（硬限制） | 建议 | 作用与取舍 |
|---|---|---|---|---|
| `sync.full-load-batch-size` | `2000` | **1–20000**，超出截断到 20000；任务级 ≤ 0 视为未设置 | **1000–5000**；宽表或大字段 500–2000 | 每批行数。批次越大提交越少、越快；超过 5000 后提速趋缓，回滚与内存成本继续上升 |
| `sync.full-load-fetch-size` | `2000` | **1–20000**，超出截断到 20000 | **1000–5000**；大字段表 100–1000；单值超 1MB 时 1–10 | 源库结果集批量，只影响源端读取。内存紧张时调小；MySQL 源固定流式读取，此值不生效 |
| `sync.full-load-parallelism` | `2` | **1–8**，且受连接池约束（见下）；任务级 ≤ 0 视为未设置 | **1–4**；共享生产库 1，专用迁移窗口 2–4 | 单表并行 worker 数。1→2 提升最明显，2→4 约再快 15–20%，再往上主要是加压源库与目标库 |
| `sync.chunk-size` | `100000` | **≥ 1**；`0` 或负数关闭分块；无上限 | **50000–200000**；不建议低于 10000 或高于 500000 | 每块行数，也是崩溃后最多重读的行数。过小则每块的边界查询与检查点写入更频繁，过大则重读与源库长事务变长 |
| `sync.max-pool-size` | `5` | 无上限，内部至少按 2 计 | **5–10**；按并行度反算，见下 | 每个数据库的连接池上限。worker 各占源、目标一条连接，池不够时只会排队 |
| `sync.initial-load-insert-first` | `true` | 开 / 关 | 开 | 空表用 INSERT 代替 upsert；已有数据的表仍走幂等 upsert |
| `sync.defer-secondary-indexes` | `true` | 开 / 关 | 开 | 非唯一二级索引延到全量完成后建；主键与唯一索引始终立即创建 |
| `写入期间暂停目标外键` | 开 | 开 / 关 | 开 | 任务级选项，避免写入顺序导致的约束失败，也减少逐行约束检查 |

**并行度与连接池的硬关系**：源库与目标库是两份不同的连接配置时，`max-pool-size` 必须 **≥ 并行度 + 1**（主流程占源、目标各一条）；两侧共用同一份连接配置时，必须 **≥ 2 × 并行度 + 2**。默认池 5 时，前者最多 4 个 worker，后者最多 1 个。并行度最终取 `min(配置值, 8, 池能容纳的 worker 数)`，被压低时日志会提示。

**超出范围的行为**：批次、fetch 超过 20000 或并行度超过 8 不会报错，而是截断到上限并在日志中提示；`chunk-size ≤ 0` 则关闭分块，整表退回单次流式读取（此时并行度不生效）。

**作用范围**：以上参数只影响首次全量与重建基线，不改变增量轮询与 CDC 回放。全局值写在 `application.yml`；批次与并行度可在任务详情页的"全量加载性能"卡片按任务覆盖（留空用全局值），其余参数只读全局配置。

### 实测基准

容器单机、50 万行 Oracle 表，批量 2000：

| 场景 | 配置 | 结果 |
|---|---|---|
| 空表全量写入方式 | `MERGE` vs `INSERT` | 约 8.4 万行/秒 vs **21.1 万行/秒** |
| 单表并行度 | 1 / 2 / 4 worker | 11.7 万 / 18.4 万 / **21.6 万行/秒** |
| 二级索引 | 边写边维护 vs 全量后创建 | 11.1s vs **5.9s + 0.5s**（约快 47%） |

> 基准用于说明参数方向，不是承诺值。实际吞吐取决于源库与目标库的磁盘、网络、驱动版本、表宽与并发负载。

### 调优步骤

1. **先跑单表基线**：并行度 1、批量 2000，记下 `rows/s` 与 `read/write/commit` 占比。
2. **看最大占比**：`commit%` 最大先把批量 2000 提到 5000；`write%` 最大先查目标库约束、索引与大字段；`read%` 最大先查源库游标与扫描范围，再把 fetch 提到 5000。
3. **再加并行度**：确认连接池足够（不同连接配置 `max-pool-size ≥ 并行度 + 1`，同一配置 `≥ 2 × 并行度 + 2`）后，从 2 加到 4，每次只改一个变量并重新看日志；4 以上收益明显递减。
4. **确认批处理生效**：`rowsPerBatch` 应接近批量大小；长期不足一半说明驱动没有批处理或大字段在拆批。
5. **生产库错峰**：与业务共享的库把并行度设为 1，批量保持 1000–2000；专用迁移窗口再逐步加并行（2–4），chunk 保持 5 万–20 万。

---

<a name="schedule"></a>
## 调度设置

调度决定"增量多久追一次"。Jync 的调度分三层：全局默认、任务级覆盖、日志 CDC 的常驻消费。

### 全局默认与任务覆盖

- **全局默认**：`sync.poll-interval`，默认 2000 毫秒，写在 `application.yml`。
- **任务级覆盖**：任务详情页打开"自定义调度"，可单独填写轮询间隔或 Cron；留空即继承全局默认。
- 调度在**任务启动后**才注册；运行中的任务需要先停止才能修改调度与同步对象。

### 轮询间隔

- 单位毫秒，实际生效范围被收敛到 **200ms – 24h**；低于 200ms 视为病态轮询，高于一天请改用 Cron。
- 间隔模式的错过的触发会被丢弃：积压的十次 2 秒轮询没有意义，下一轮本来就会看到全部变化。

### Cron

- 使用 Quartz 语法，**6 段或 7 段，秒在最前**。
- 填写后**优先于轮询间隔**；表达式在保存与启动时都会校验，非法值直接拒绝。
- **按服务器时区求值**。服务器跑在 UTC 时，`0 0 2 * * ?` 会在本地时间上午 10 点触发；部署时请固定 `-Duser.timezone` 或容器 `TZ`。任务详情页会显示"下次自动运行"及其实时区。
- Cron 的错过的触发会在有空闲线程时**补跑一次**，而不是静默跳过一整天。

```text
0/2 * * * * ?        每 2 秒
0 0/5 * * * ?        每 5 分钟
0 30 2 * * ?         每天 02:30
0 0 8 ? * MON-FRI    工作日 08:00
```

### 启动、补跑与多实例

- **启动即跑一轮**：任务启动后会立即执行一次，然后按调度运行，不必等第一个间隔。
- **立即同步**：不改变启用状态，从上次成功位置补跑一轮；运行中点击会合并为一轮补跑，不会丢弃。
- **停止同步**：取消调度，并让**正在执行的一轮**在下一个已提交批次边界安全退出（已写入的数据与断点保留），随后释放同步锁——不必等这一轮跑完才能改配置。
- **CDC 任务没有 Quartz 调度**：长驻消费者持续回放，轮询间隔、Cron 与游标列都不生效，页面会隐藏调度卡片；空闲挖掘间隔按引擎单独配置（最低 50ms）。
- **多实例**：Quartz 使用内存存储，节点启动时按数据库里的启用标志重建调度；对账线程每 30 秒把本地调度与共享库对齐。同一任务的实际执行由数据库租约锁互斥。

---

<a name="cleansing"></a>
## 列清洗

列清洗在**写入目标库之前**对字符串列做固定规则处理，源库数据不受影响。它适合处理历史数据里的空格、大小写、前后缀与格式噪声，而不把 Jync 变成脚本引擎。

### 规则

| 规则 | 作用 | 示例 |
|---|---|---|
| `TRIM` | 去首尾空白（Unicode 空白） | `"  张三 "` → `"张三"` |
| `UPPER` | 转大写 | `"ab-01"` → `"AB-01"` |
| `LOWER` | 转小写 | `"AB-01"` → `"ab-01"` |
| `BLANK_TO_NULL` | 空串或纯空白转 `NULL` | `"   "` → `NULL` |
| `STRIP_PREFIX` | 去掉一个固定前缀 | 前缀 `CN-`：`"CN-001"` → `"001"` |
| `STRIP_SUFFIX` | 去掉一个固定后缀 | 后缀 `.csv`：`"a.csv"` → `"a"` |
| `REGEX_REPLACE` | 用正则替换全部匹配 | `\s+` → 空格；`^(\d{4})-` → `$1` |

### 执行顺序与范围

- 规则按"表 + 列"配置，表名与列名**大小写不敏感**。
- 同一张表内的规则按**保存顺序依次执行**：先 `TRIM` 再 `BLANK_TO_NULL`，纯空白值才会变成 `NULL`。
- **只处理字符串值**，数字与日期原样通过，不做隐式类型转换。
- 全量、增量轮询与 CDC 回放走同一套规则；并行全量的每个 worker 各自执行。
- 被清洗的列会被强制整列物化读取，不受流式大字段策略影响；单值超过 `sync.lob.force-inline-max-bytes`（默认 1MB）会明确报错并指出表、列与配置键。
- 运行中的任务需先停止同步，才能修改列清洗规则。

### 校验

- `STRIP_PREFIX` / `STRIP_SUFFIX` / `REGEX_REPLACE` 必须填写参数。
- 正则按 Java 语义在保存时校验，`$1` 分组引用、`${name}` 命名分组、反斜杠转义都支持；非法表达式与非法替换串直接拒绝，不会等到同步周期才失败。
- 同一列完全相同的规则不允许重复保存，避免被执行两次。

---

<a name="databases"></a>
## 支持的数据库

内置 **26 种**：MySQL、MariaDB、Oracle、SQL Server、DB2、PostgreSQL、openGauss、**华为 GaussDB**、**达梦**、**人大金仓**、**OceanBase**、**TiDB**、**中兴 GoldenDB**、**腾讯 TDSQL（MySQL 版）**、**阿里 PolarDB（MySQL 版）**、**万里 GreatDB**、**银联 UPDRDB**、**星环 KunDB**、**平安 RASESQL**、**东方金信 SeaboxSQL**、**瀚高**、**海量 Vastbase**、**崖山**、**南大通用 GBase**、**神通 Oscar**、H2。

- **自定义数据库**：JDBC URL + 驱动类名 + 驱动 jar 路径，运行时独立 `URLClassLoader` 加载；可指定方言家族（通用 / Oracle / MySQL / PostgreSQL / SQL Server / DB2），决定建表、upsert 与元数据读取方式。
- **驱动内置**：除 **GBase**、**神通** 与 **华为 GaussDB** 外均随包内置。这三家未在 Maven Central 发布官方构件，需自备 jar 并填写路径；厂商 jar 的驱动类名与预设不同时可在同一张卡片覆盖。
- **华为 GaussDB**：与 openGauss 是两个产品，驱动 `com.huawei.gaussdb.jdbc.Driver`、URL 前缀 `jdbc:gaussdb://`，方言归入 PostgreSQL 系。
- **协议兼容预设**：万里 GreatDB、银联 UPDRDB、星环 KunDB 兼容 MySQL 协议；平安 RASESQL、东方金信 SeaboxSQL 兼容 PostgreSQL 协议。它们复用内置驱动与对应方言族，表结构、索引、视图、过程与 upsert 走同一条已验证链路。
- **PolarDB / TDSQL 分版本**：MySQL 版有独立预置；PostgreSQL 版选 PostgreSQL 预置；PolarDB Oracle 兼容版协议是 PostgreSQL、驱动为 `com.aliyun.polardb2.Driver`，请用自定义类型。
- **许可证**：驱动版本与许可证见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

---

<a name="deploy"></a>
## 部署

### 环境要求

| 项 | 要求 |
|---|---|
| JDK | **17+** |
| Maven | 3.6+（仅源码编译需要） |
| 端口 | 默认 `8080` |
| 内存 / 磁盘 | 建议堆 ≥ 512MB，预留 1GB；大字段同步按最大单值放大堆 |
| 工作目录 | 必须固定：`./data`（元数据库与密钥）与 `./logs` 相对进程工作目录生成 |

首次启动会在工作目录创建 `./data` 与 `./logs`。**不要在不同目录反复启动**，否则会看到"配置都不见了"——实际是连到了另一个空的元数据库。

### 1. 准备目录

```bash
sudo mkdir -p /opt/jync
sudo chown -R jync:jync /opt/jync
cd /opt/jync
```

建议的目录结构：

```text
/opt/jync/
├── jync.jar
├── application.yml
├── data/          # H2 元数据库与 <库名>.key，必须备份
└── logs/
```

### 2. 获取 jar

从发布页下载 `jync.jar`：

- GitHub：<https://github.com/vfaner/jync/releases/latest>
- Gitee：<https://gitee.com/super_rgh/jync/releases/latest>
- AtomGit（镜像）：<https://gitcode.com/vfaner/jync/releases/latest>

源码编译：

```bash
git clone https://github.com/vfaner/jync.git
cd jync
mvn clean package -DskipTests
cp target/jync.jar /opt/jync/
```

国内可用 Gitee：`git clone https://gitee.com/super_rgh/jync.git`，或 AtomGit 镜像：`git clone https://gitcode.com/vfaner/jync.git`。不跳过测试会执行约 2500 个用例。

### 3. 生产配置

在 jar 同级目录创建 `application.yml`：

```yaml
server:
  port: 8080

sync:
  poll-interval: 2000              # 全局轮询间隔（毫秒）
  batch-size: 500                  # 增量 / CDC 回放每批行数（在线流量，保持小）
  fetch-size: 1000                 # 增量读取结果集批量
  full-load-batch-size: 2000       # 仅首次全量：每批行数（1-20000，建议 1000-5000）
  full-load-fetch-size: 2000       # 仅首次全量：源端结果集批量（1-20000）
  full-load-parallelism: 2         # 全量并行 worker 数（1-8，建议 1-4，受连接池约束）
  chunk-size: 100000               # 全量每块行数（建议 5 万-20 万；0 关闭分块）
  initial-load-insert-first: true  # 空表用 INSERT 代替 upsert
  defer-secondary-indexes: true    # 全量完成后再建非唯一二级索引
  max-pool-size: 5                 # 每库连接池上限（建议 5-10，须容纳并行度）
  crypto-password: ""              # 留空 = 全新库首启自动生成，保存到 data/jync.key
  crypto-salt: ""                  # 留空 = 同上；需要自管密钥再显式填写

  # ai:
  #   enabled: false               # 内网 / 离线：关闭 AI 辅助

app:
  login:
    # 应急通道：关闭本地登录后若外部认证不可用，设为 true（或启动时加
    # JYN_RESET_LOGIN=true）启动一次，会重新开启本地登录并保存；修好后改回 false。
    reset-on-startup: false
  # 内网 / 离线：关闭版本检查
  # update-check:
  #   enabled: false
```

**外部元数据库（可选）**

不想要 H2 文件库时，启动前把 `spring.datasource.*` 指向已有数据库即可，表结构由 `ddl-auto: update` 在首启自动创建：

| 元数据库 | 驱动类 | URL 示例 | 验证状态 |
|---|---|---|---|
| H2（默认） | `org.h2.Driver` | `jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE` | 默认路径 |
| MySQL 8 | `com.mysql.cj.jdbc.Driver` | `jdbc:mysql://db:3306/jync?useUnicode=true&characterEncoding=utf8` | 有容器契约测试 |
| PostgreSQL 16 | `org.postgresql.Driver` | `jdbc:postgresql://db:5432/jync` | 有容器契约测试 |
| Oracle 23 | `oracle.jdbc.OracleDriver` | `jdbc:oracle:thin:@//db:1521/FREEPDB1` | 有容器契约测试 |

```yaml
spring:
  datasource:
    url: "jdbc:mysql://db:3306/jync?useUnicode=true&characterEncoding=utf8"
    username: jync
    password: change-me
    driver-class-name: com.mysql.cj.jdbc.Driver   # 能按 URL 推断时可省略
```

也可用 `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` / `SPRING_DATASOURCE_DRIVER_CLASS_NAME` 环境变量覆盖。要点：

- **先建库**：MySQL 用 `CREATE DATABASE jync DEFAULT CHARACTER SET utf8mb4;`；其余类型建空库即可。所需 JDBC 驱动已随包内置，不用另外放 jar。
- **必须显式设置 `SYNC_CRYPTO_PASSWORD` / `SYNC_CRYPTO_SALT`**：自动生成的密钥依附于本地 `<库名>.key`，外部元数据库场景没有这个文件，否则会退回发行包里的公开默认密钥。
- **已验证范围**：MySQL 8、PostgreSQL 16、Oracle 23 有真实数据库契约测试（建表、自增主键、`@Lob` 往返、批量更新/删除、聚合查询）。SQL Server 与国产库未逐一实测为元数据库；PostgreSQL / Oracle 兼容族的国产库（金仓、openGauss、瀚高、Vastbase、达梦、崖山等）理论上同族可用，但 Hibernate 方言识别因驱动而异，**上生产前请先在测试环境用同一驱动启动验证**。
- **换库不是迁移**：改用外部元数据库等于从一份空元数据开始，原 H2 里的连接、任务、游标与检查点不会自动带过去；新部署可以直接用，已有部署请保留原 H2，或自行迁移这些数据。
- **多副本的前提**：H2 的 `AUTO_SERVER=TRUE` 只能同机多进程共享；跨主机多实例必须使用上表中的外部元数据库。

启动命令：

```bash
cd /opt/jync
java -Xms512m -Xmx2g -Duser.timezone=Asia/Shanghai \
  -jar jync.jar --spring.config.additional-location=file:./application.yml
```

> ⚠️ 必须使用 `--spring.config.additional-location`（叠加覆盖）。替换式的 `spring.config.location` 会丢掉 jar 内置配置，症状是登录页显示 `??login.title_zh_CN??`、全新部署不建表。
>
> 🕐 `-Duser.timezone` 同时决定 Cron 的求值时区。服务器默认是 UTC 时，务必显式指定。

### 4. 首次登录

访问 `http://<服务器>:8080`：

| 用户名 | 角色 | 初始密码 |
|---|---|---|
| `admin` | 管理员 | `123456` |
| `view` | 访客 | `123456` |

**首次登录后必须修改密码**：未修改前只能停留在改密页，其他页面与 API 一律不可用。密码使用 BCrypt 存储。

### 5. 注册为系统服务

`/etc/systemd/system/jync.service`：

```ini
[Unit]
Description=Jync heterogeneous database sync
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=jync
Group=jync
WorkingDirectory=/opt/jync
ExecStart=/usr/bin/java -Xms512m -Xmx2g -Duser.timezone=Asia/Shanghai \
  -jar /opt/jync/jync.jar --spring.config.additional-location=file:/opt/jync/application.yml
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now jync
sudo systemctl status jync
```

### 6. Docker 与 Compose

```bash
docker build -t jync:1.8.1 .
docker run -d --name jync -p 8080:8080 \
  -v "$PWD/data:/app/data" \
  -v "$PWD/logs:/app/logs" \
  -e TZ=Asia/Shanghai \
  --restart unless-stopped jync:1.8.1
```

或使用 Compose：

```bash
docker compose up -d --build
```

- 运行镜像仅含 JRE，非 root 运行，内置 `HEALTHCHECK`（探测 `/login`）。
- 元数据与日志分别落在宿主机 `./data`、`./logs`，重建镜像不丢数据。
- 环境变量、TLS、多实例与排障见 [docs/deploy-container.md](docs/deploy-container.md)。

### 7. Kubernetes

```bash
docker build -t registry.example.com/qqmu/jync:1.8.1 .
docker push registry.example.com/qqmu/jync:1.8.1
kubectl apply -k deploy/k8s/
kubectl -n jync get pods
```

- 清单默认镜像 `jync:1.8.1`，可用 `kustomization.yaml` 的 `images` 变换改成自己的仓库与新 tag。
- Service 为 `ClusterIP`，集群外访问用 `kubectl -n jync port-forward svc/jync 8080:80` 或配置 Ingress。
- H2 元数据库是 `ReadWriteOnce` PVC，**副本数必须为 1**。需要多副本时先把元数据库换成外部 MySQL / PostgreSQL（`SPRING_DATASOURCE_*` 环境变量），再调大 `replicas`。

### 8. 反向代理与 HTTPS

Nginx 示例：

```nginx
server {
    listen 443 ssl;
    server_name jync.example.com;

    ssl_certificate     /etc/nginx/certs/jync.crt;
    ssl_certificate_key /etc/nginx/certs/jync.key;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
}
```

HTTPS 部署请设置 `JYN_COOKIE_SECURE=true`。使用网关头统一认证时，还必须把网关地址加入 `app.security.trusted-proxies`，并确保网关剥离客户端伪造的身份头。

### 9. 升级与备份

1. 停止服务，备份 `./data`（连同 `<库名>.key`）、`./logs` 与 `application.yml`。
2. 用新版 `jync.jar` 替换旧文件，重新启动；表结构随 `ddl-auto: update` 自动演进。
3. 停机期间源库发生的变更，重启后由增量或 CDC 自动补齐。
4. 管理员也可在"系统信息"页使用内置版本检查与一键升级，旧 jar 会保留为 `jync.jar.bak`；重启脚本会带上当前进程的 JVM 参数与程序参数（JDWP 调试参数除外），`-Xmx`、`-D` 属性与 `--spring.profiles.active` 等不会丢失。

> 🔐 `<库名>.key` 与元数据库必须成对备份。密钥丢失后，已保存的连接密码与 AI 密钥将无法解密，只能重新填写。

### 10. 内网与离线

- `sync.ai.enabled=false`：隐藏 AI 菜单，AI 起草在发请求前直接拦截。
- `app.update-check.enabled=false`：不再访问 GitHub / Gitee，升级入口同步隐藏。
- 所有静态资源本地化，不使用 CDN 与 webfont。

---

<a name="config"></a>
## 配置项

`application.yml` 的主要配置（`sync.*` 前缀）：

| 键 | 默认 | 说明 |
|---|---|---|
| `sync.poll-interval` | `2000` | 全局轮询间隔（毫秒） |
| `sync.batch-size` | `500` | 增量轮询与 CDC 回放的 JDBC 批次行数 |
| `sync.fetch-size` | `1000` | 增量读取的结果集批量 |
| `sync.full-load-batch-size` | `2000` | 仅首次全量：每批行数，范围 1–20000，建议 1000–5000 |
| `sync.full-load-fetch-size` | `2000` | 仅首次全量：源端结果集批量，范围 1–20000 |
| `sync.full-load-parallelism` | `2` | 单表全量并行 worker 数，范围 1–8，建议 1–4，受连接池约束 |
| `sync.chunk-size` | `100000` | 全量每块行数，建议 50000–200000；`0` 关闭分块 |
| `sync.initial-load-insert-first` | `true` | 空目标表用 INSERT 代替 upsert |
| `sync.defer-secondary-indexes` | `true` | 非唯一二级索引延到全量完成后创建 |
| `sync.max-pool-size` | `5` | 每个数据库连接池上限，建议 5–10；并行度还会被池上限反过来限制 |
| `sync.safety-lag-ms` | `1000` | 时间戳水位线回退量 |
| `sync.full-compare-max-rows` | `20000` | 自动选用全表比对的行数上限；手动在任务级选择时不生效，超出时页面与启动日志会提示 |
| `sync.row-count-audit-interval-ms` | `60000` | 行数审计间隔；`0` 关闭 |
| `sync.lock-ttl-ms` | `300000` | 同步锁租约时长 |
| `sync.cdc.reconnect-backoff-ms` | `1000` | CDC 断线重连初始退避 |
| `sync.cdc.reconnect-backoff-max-ms` | `60000` | 重连退避上限 |
| `sync.cdc.server-id-offset` | `612000` | MySQL 假 replica server id 基数，实际 id = 偏移 + 任务 ID |
| `sync.cdc.lock-ttl-ms` | `30000` | CDC 消费者租约时长 |
| `sync.cdc.supervise-interval-ms` | `10000` | 各节点巡检并抢占 CDC 租约的周期 |
| `sync.cdc.connect-timeout-ms` | `10000` | BinaryLogClient 握手超时 |
| `sync.cdc.logminer.batch-size` | `10000` | LogMiner 单批读取行数 |
| `sync.cdc.logminer.idle-poll-ms` | `1000` | LogMiner 真空闲时的休眠间隔 |
| `sync.cdc.pg.idle-poll-ms` | `500` | PostgreSQL 逻辑解码真空闲时的休眠间隔 |
| `sync.cdc.mssql.idle-poll-ms` | `1000` | SQL Server CDC 真空闲时的休眠间隔 |
| `sync.cdc.change-window-ms` | `10000` | CDC 变更日志按表聚合窗口；`0` 关闭聚合 |
| `sync.lob.streaming-enabled` | `true` | 大字段流式读写总开关 |
| `sync.lob.inline-max-bytes` | `65536` | 不超过该字节数的大字段值留在堆内存 |
| `sync.crypto-password` / `sync.crypto-salt` | *(留空自动生成)* | 连接密码加密密钥；全新库首启生成并写入 `<库名>.key`，旧库先用默认密钥启动再自动迁移 |
| `sync.ai.enabled` | `true` | 置 `false` 则 AI 菜单与接口一并下线 |
| `app.update-check.enabled` | `true` | 新版本检查；关闭后不发起外网请求 |
| `app.update-check.match-artifact` | `true` | 版本号相同时再比对安装包（GitHub 摘要 / Gitee 大小），仅「证明不同」才提示 |

> 连接密码以 AES-256 加密存储，`enc:` 前缀防止重复加密。密钥留空时首启自动生成；一旦显式填写，就不会再生成 `<库名>.key`。

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
- **恢复本地登录**：以 `JYN_RESET_LOGIN=true` 启动一次（等价于在 `application.yml` 里设 `app.login.reset-on-startup: true`），Jync 会重新打开本地登录并保存；登录后修好外部配置，去掉变量并改回 `false` 重启。
- 嵌入 iframe 需要 HTTPS，并同时设置 `JYN_COOKIE_SECURE=true` 与 `JYN_COOKIE_SAME_SITE=None`。

---

<a name="workflow"></a>
## 使用流程

1. **数据库连接** → 新建源 / 目标连接 → "测试连接"确认可用。
2. **同步任务** → 新建任务，选定源库与目标库。
3. **任务详情** → 勾选表 / 视图 / 存储过程；需要改目标表名时直接在右侧填写（留空即同名）。
4. 按需配置**捕获策略、调度、全量加载性能与列清洗**。
5. "**立即同步**"验证一次，或"**开启自动同步**"进入持续增量。
6. 在"**变更日志**"查看每轮明细，在"**看板**"查看整体状态。首次全量时若目标表已有部分相同数据，条目会写成 `50 row(s) inserted (53 scanned, 3 already up to date)`——扫描多少、真正写入多少、多少行已是最新一目了然，可直接与目标表总行数对照。
7. 需要确认两边数据是否一致时，在任务详情的「各表同步进度」点「**核对行数**」：确认后逐表核对，结果标出一致 / 不一致 / 缺失 / 超时。

---

<a name="ai"></a>
## AI 辅助转换（可选）

存储过程的过程化控制流无法靠文本替换可靠翻译。`AI 配置` 提供另一条路：模型**起草候选**，人工审阅后落库。

- **四种协议**：OpenAI 兼容、Anthropic、Ollama（OpenAI 兼容端点）、Ollama（原生 `/api/chat`）。
- **硬边界**：AI 只产候选，**永不参与同步**；候选经人工确认后才写入 DDL 覆盖；未启用供应商时不产生任何外部请求。
- **真实探测**：保存前发送一次 `max_tokens: 1` 的真实请求，而不是只探 TCP。
- **审阅页**：左右对照源定义与机械转换结果；模型自述的"不确定项"比 SQL 本身更值得先读。
- **深度思考档位**：可跟随模型、关闭、低、高；Anthropic 的思考预算按协议发 `thinking.budget_tokens`，OpenAI 形状发 `reasoning_effort`。

> ⚠️ 转换会把存储过程源码发往你配置的端点。内网或合规部署请接本地推理端点（本机装了 Ollama 可直接选本地预设）。

---

<a name="comparison"></a>
## 与市面工具的对比

> 口径：截至 2026-10，依据各工具官方文档与开源版默认部署形态，仅比较与本工具定位重叠的"开箱即用的异构库持续同步"能力。各工具在自身定位上更强，本表不构成对其整体评价；如有出入，以官方文档为准。更完整的分维度对比与适用边界见 <https://jync.qqmu.com/compare.html>。

| 维度 | **Jync** | Debezium + Kafka | DataX | Kettle | Navicat / DBeaver 传输 |
|---|---|---|---|---|---|
| 部署形态 | **单个 jar** | Kafka + Connect + ZK | 客户端脚本 | 桌面 + 资源库 | 桌面客户端 |
| 配置方式 | **Web 点选** | YAML / REST + 消费端代码 | JSON 作业 | 图形 ETL | 向导 |
| 上手速度 | **分钟级**：下载 jar、启动、点选 | 天级：先搭 Kafka 全家桶 | 小时级：手写 JSON 作业 | 天级：理解转换与作业模型 | 分钟级，但仅一次性迁移 |
| 持续增量 | 轮询 / Cron + 日志 CDC（MySQL binlog、Oracle LogMiner、PostgreSQL、SQL Server） | 日志级 | 不适用（一次性） | 部分（自建定时） | 部分（计划任务 / 全表比对） |
| 全量加载 | **keyset 分块 + 表内并行 + 流式读取 + 检查点** | 支持初始 / 增量快照；异构目标落库需自写消费端 | 分片作业 | 作业内实现 | 一次性向导 |
| 结构同步 | **自动建表 / 索引 / 视图 / 过程** | 部分，落库需自写 | 需预建表 | 部分 | 仅一次性 |
| 异构方言转换 | **类型 / 函数 / 引号 / 分页 / 过程** | 不支持 | 部分 | 部分，需手工 | 部分 |
| 任务级加工与运维 | **列清洗、目标表名映射、自定义间隔 / Cron、全量批量与并行度、源 / 目标数据预览与按需行数核对，全部在页面配置** | SMT 或自定义代码 | JSON 作业 | 转换步骤 | 仅客户端预览 |
| 国产数据库 | **达梦 / 金仓 / OceanBase 等 10 种以上一等公民** | 部分 | 部分 | 部分 | 部分 |
| 源库侵入 | **默认只读零侵入**；CDC 需复制权限但仍只读 | 需开 binlog / WAL | 只读 | 只读 | 只读 |
| 断点续传 / 幂等 | **游标持久化 + 主键 upsert + 检查点** | offset | 不适用 | 部分 | 不适用 |
| 移动端 / 响应式 | **900px 以下切换抽屉导航，640px 以下角色选择与字段映射纵向堆叠，宽表横向滚动；手机浏览器可查看进度、启停任务与轻量配置** | Connector 管理 UI，无业务端移动适配 | 无界面（命令行） | 桌面客户端 | 桌面客户端为主 |
| 运维成本 | **一个进程** | 流式平台 + 消费端 | 需外部调度 | 资源库 + 调度 | 客户端计划任务 / 人工 |

**八个差异点**

1. **一个 jar 对一套流式基础设施**：规模配不上运维成本时，差距是决定性的。
2. **分钟级上手**：实施人员按向导配置即可，不需要团队先掌握流式平台。
3. **结构与数据一起同步**：建表、类型映射、索引与过程转换不再是你自己的作业。
4. **为国产库与信创迁移而生**：方言有专门实现，而不是"通用 JDBC 也许能连"。
5. **默认轮询零侵入**：一个只读账号即可；需要秒级时再切换日志 CDC。
6. **全量也为持续同步设计**：分块、并行、流式与检查点，让第一次装载同样是可恢复、可观测的工程流程。
7. **手机浏览器也能操作**：导航自动收成抽屉，角色选择与字段映射在窄屏纵向堆叠，宽表横向滚动；查看进度、启停任务不必回到电脑前。
8. **迁移现场的琐碎配置不用写代码**：列清洗、目标表名映射、自定义调度、全量批量与并行度、源 / 目标数据预览与按需行数核对都在任务页完成；同一套清洗规则同时作用于全量、增量与 CDC。

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
│   ├── cdc          binlog / LogMiner / PG / SQL Server 消费者
│   ├── task         Quartz 调度、三层锁、上下文装配、启动恢复
│   ├── lob          大字段流式读取、绑定与落盘
│   └── ai           供应商配置、共享 HTTP 层、候选起草与审阅
├── model / repository / dto / util
```

- **三层锁**：Quartz `@DisallowConcurrentExecution` → JVM 内按任务 `ReentrantLock` → 数据库租约锁（跨重启与跨实例）。
- **事务语义**：游标推进与锁获取使用 `REQUIRES_NEW`，避免与数据写入同事务回滚。
- **调度对账**：多实例部署时，每个节点把本地 Quartz 调度与共享库中的启用标志对齐。

---

<a name="docs"></a>
## 文档与测试

- **容器部署与运维**：[docs/deploy-container.md](docs/deploy-container.md)（环境变量、TLS、多实例、升级与排障）
- **已知限制与边界**：[docs/known-limitations.md](docs/known-limitations.md)（CDC 边界、无主键表、大字段、过程转换）
- **Oracle → 达梦迁移实战**：[docs/migration-harness-oracle-to-dm.md](docs/migration-harness-oracle-to-dm.md)
- **项目介绍文章**：[docs/jync-article.md](docs/jync-article.md)
- **版本记录**：[GitHub Releases](https://github.com/vfaner/jync/releases) · [Gitee Releases](https://gitee.com/super_rgh/jync/releases) · [AtomGit 镜像](https://gitcode.com/vfaner/jync/releases)
- **第三方驱动许可证**：[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)

```bash
mvn test
```

**2604 个测试：2603 通过，1 个仅限 Linux 的密钥文件权限用例在 macOS 上按环境跳过。**

重点覆盖：

- **方言与 SQL**：各方言幂等 upsert、类型上限、SQL 正文改写不碰字面量与注释。
- **全量与续传**：keyset 分块、并行区间不相交、无主键 OFFSET 续传、检查点、列清洗与主键边界。
- **安全**：密码与 AI 密钥加密、访客 POST 全拒、CSRF 缺失即拒、外部认证模式隔离。
- **CDC**：四类引擎的前提探测、位点纪律、租约互斥、故障摘要。
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
