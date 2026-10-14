# Jync 捷同 · 异构数据库实时同步工具

> **Jync**，读作 "sync" —— **J** 代表 Java；**捷同**：敏捷同步，两库皆同。
> 单 JAR 开箱即用 —— 不要 Kafka，不要 ZooKeeper，不要一行代码。

**简体中文** | [English](README_EN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen.svg)](https://spring.io/projects/spring-boot)

![Jync 捷同 · 异构数据库实时同步工具](docs/images/jync.png)

在**异构数据库**之间实时同步**结构与数据**的开箱即用 Web 应用：单个 jar 启动，浏览器点几下即可在**内置 17 种数据库（另支持自定义类型）之间任意两种**建立持续同步链路。增量检测默认轮询（零侵入），MySQL / Oracle / PostgreSQL / SQL Server 源库可切换**日志 CDC** 做到秒级延迟。Spring Boot 2.7 单体 + Thymeleaf 服务端渲染 + Quartz + H2 内嵌元数据库，**零外部依赖，内网离线可用**。

- 项目地址：<https://github.com/vfaner/jync>　国内镜像：<https://gitee.com/super_rgh/jync>
- 视频演示：<https://www.bilibili.com/video/BV1iHYJ6vEEd>（14 分钟，从建连接到实时同步）

---

## 界面速览

<table>
  <tr>
    <td align="center" width="50%"><img src="docs/images/jync_login.png" alt="登录" width="100%"><br><sub><b>登录</b> · 口令门禁，初始密码未改时全页挂警告</sub></td>
    <td align="center" width="50%"><img src="docs/images/jync_kanban.png" alt="看板" width="100%"><br><sub><b>看板</b> · 项目/连接/表数、24h 变更量与最近活动</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/jync_kanban_anye.png" alt="暗色主题" width="100%"><br><sub><b>暗色主题</b> · 整套配色随 <code>data-theme</code> 一起换</sub></td>
    <td align="center"><img src="docs/images/jync_kanban_en.png" alt="英文界面" width="100%"><br><sub><b>英文界面</b> · 中 / EN 一键切换，默认按时区推断</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/jync_db.png" alt="数据库连接" width="100%"><br><sub><b>连接管理</b> · 源 / 目标分栏，各自独立分页</sub></td>
    <td align="center"><img src="docs/images/jync_db_add.png" alt="新增连接" width="100%"><br><sub><b>新增连接</b> · 类型选好后 JDBC URL 自动拼装，先测通再保存</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/jync_xiangmu.png" alt="项目列表" width="100%"><br><sub><b>项目列表</b> · 多任务并行启停，分页可跳页</sub></td>
    <td align="center"><img src="docs/images/jync_xiangmu_add.png" alt="新建项目" width="100%"><br><sub><b>新建项目</b> · 源与目标各选一条已测通的连接</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/jync_xl.png" alt="项目详情" width="100%"><br><sub><b>项目详情 · 轮询</b> · 逐表勾选、游标策略标注与手动指定</sub></td>
    <td align="center"><img src="docs/images/jync_cdc.png" alt="日志 CDC" width="100%"><br><sub><b>项目详情 · 日志 CDC</b> · 位点 / 落后秒数 / 吞吐 / 跳过计数面板</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/jync_log.png" alt="变更日志" width="100%"><br><sub><b>变更日志</b> · 对象、类型、行数、耗时与错误详情</sub></td>
    <td align="center"><img src="docs/images/jync_ai.png" alt="AI 配置" width="100%"><br><sub><b>AI 配置</b> · 多供应商共存，同一时刻只启用一个</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/jync_ai_add.png" alt="新增供应商" width="100%"><br><sub><b>新增供应商</b> · 保存前发真实请求探测端点</sub></td>
    <td align="center"><img src="docs/images/jync_xiangmu_suoyin_ai.png" alt="AI 起草" width="100%"><br><sub><b>AI 起草</b> · 候选 SQL 流式进编辑器，保存前人工审阅</sub></td>
  </tr>
  <tr>
    <td align="center" colspan="2"><img src="docs/images/jync_system.png" alt="系统信息" width="60%"><br><sub><b>系统信息</b> · 运行参数、版本与更新内容只读集中展示</sub></td>
  </tr>
</table>

---

## 核心功能

| 分类 | 能力 |
|---|---|
| 项目 / 连接 | 多项目并行互不干扰；连接按源 / 目标分栏独立分页；保存前可测连通并预览自动拼装的 JDBC URL |
| 对象与结构 | 表 / 视图 / 存储过程分组勾选（搜索 + 批量）；目标库缺失时按目标方言自动建表 / 索引 / 视图 / 过程 |
| 方言适配 | 类型映射、函数名转换、标识符引号、分页语法、存储过程包装 |
| 实时同步 | 轮询（默认 2s）或 Cron 编排；四种日志 CDC（MySQL binlog / Oracle LogMiner / PG 逻辑解码 / SQL Server CDC）秒级延迟 |
| 可靠性 | 全量按主键 keyset 分块 + 检查点续传；单列整数主键表内并行（上限 8）；重启从游标继续并补齐停机变更；写入全部幂等 |
| 控制与观测 | 启停 / 立即同步（运行中点击合并为一轮补跑）；看板 + 变更日志 + 行数审计 |
| 前端 | 中 / 英切换（默认按时区推断）、昼夜主题（跟随系统）、无 CDN 无 webfont，静态资源约 440KB 全本地化 |

---

## 支持的数据库

MySQL、MariaDB、Oracle、SQL Server、DB2、PostgreSQL、OpenGauss、**达梦**、**人大金仓**、**OceanBase**、**TiDB**、**瀚高**、**海量**、**崖山**、**南大通用 (GBase)**、**神通 (Oscar)**、H2，以及**自定义数据库**（JDBC URL + 驱动类名 + 驱动 jar 路径，运行时动态加载）。

除 **GBase** 与 **神通** 外驱动均随包内置；这两家未在 Maven Central 发布官方构件，需自备 jar 并在连接配置填路径（独立 `URLClassLoader` 加载，不污染主程序）。驱动版本与许可证见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。PolarDB 按兼容协议选 MySQL / PostgreSQL 类型即可。

---

## 快速开始

| 项 | 要求 |
|---|---|
| JDK | **17+**（Maven 3.6+ 仅构建时需要） |
| 端口 / 内存 / 磁盘 | 默认 `8080`；建议 ≥ 512MB 堆；预留 1GB |

```bash
git clone https://github.com/vfaner/jync.git   # 国内：git clone https://gitee.com/super_rgh/jync.git
cd jync && mvn clean package -DskipTests
java -jar target/jync.jar                      # 访问 http://localhost:8080
```

首次启动在**当前工作目录**创建 `./data`（H2 元数据库）与 `./logs`；请固定目录启动，或用绝对路径覆盖配置。

### 生产环境配置

jar 同级目录建 `application.yml`，只写要覆盖的键：

```yaml
server:
  port: 8080
spring:
  datasource:
    url: jdbc:h2:file:/opt/jync/data/jync;MODE=MySQL;AUTO_SERVER=TRUE
sync:
  crypto-password: 请改成你自己的强口令      # ← 必须修改
  crypto-salt: 请改成你自己的16位十六进制盐   # ← 必须修改
logging:
  file:
    path: /opt/jync/logs
```

```bash
java -jar jync.jar --spring.config.additional-location=file:./application.yml
```

> ⚠️ 必须用 `additional-location`（叠加覆盖），不要用替换式的 `spring.config.location`：后者会丢掉 jar 内置配置（i18n basename、`ddl-auto`、`app.github-url`），症状是登录页 `??login.title_zh_CN??`、全新部署不建表甚至启动失败。
>
> 🔐 `crypto-password` / `crypto-salt` 加密存储的数据库密码，发行包带默认值，**生产必须改**；改后旧密码需重填。

**后台常驻**：systemd 单元把 `ExecStart` 指向上面的启动命令（`WorkingDirectory=/opt/jync`，`Restart=always`）即可；快速验证用 `nohup java -jar jync.jar > /dev/null 2>&1 &`。**容器部署**：仓库自带 `Dockerfile`、`docker-compose.yml` 与 `deploy/k8s/`（Kustomize），环境变量、TLS、升级与排障见 [docs/deploy-container.md](docs/deploy-container.md)。**升级**：停服 → 替换 jar → 启动，表结构随 `ddl-auto: update` 自动演进，**升级前备份 `./data`**；停机期间源库变更重启后自动补齐。

---

## 登录与权限

| 用户名 | 角色 | 初始密码 | 权限 |
|---|---|---|---|
| `admin` | 管理员 | `123456` | 全部操作 |
| `view` | 访客 | `123456` | 只读 |

首次启动种入（表非空则跳过，不重置任何人的密码）；**第一次登录后请立即改密**。权限按 HTTP 方法判定，只有一条规则：**POST 一律要求管理员** —— 访客能看全部页面与数据、能复制 SQL，但界面上没有任何写按钮，构造请求也会被拒。改密入口：右上角用户名 → 修改密码（`/account/password`）；密码 BCrypt 存储，**忘记无法找回**，只能删 `app_user` 对应行后重启重新种入。

---

## 使用流程

1. **数据库连接** → 新建源 / 目标连接 → 「测试连接」确认可用
2. **项目** → 新建项目，选定源库与目标库
3. **项目详情** → 勾选表 / 视图 / 存储过程，配置同步选项后保存
4. 「**立即同步**」验证一次，或「**启动同步**」开始持续增量
5. 「**变更日志**」查看每次同步明细

---

## 增量检测策略

增量只有一条查询：`WHERE 游标列 > 上次游标 AND 游标列 <= 本次水位`。**一行能否被同步，只取决于游标列值有没有涨** —— 所以游标列必须是「改一行它就会变大」的列（如 `update_time`）；用 `id` 或 `create_time` 做游标时 UPDATE 永远同步不到。默认轮询模式不碰源库 binlog、不建触发器，只需一个只读账号；切换[日志 CDC](#日志-cdc)后 UPDATE / DELETE 直接可见，上述限制不复存在。

| 策略 | 触发条件 | 新增 | 更新 |
|---|---|:---:|---|
| `TIMESTAMP` | 存在符合「最后修改时间」命名约定的时间类型列 | ✅ | ✅ 前提是该列真被更新 |
| `ROW_SCN` | Oracle 且建表带 `ROWDEPENDENCIES`（行级 `ORA_ROWSCN`） | ✅ | ✅ 检测不到删除 |
| `IDENTITY` | 存在「创建时间」约定列或单列数字主键 | ✅ | ❌ |
| `FULL_COMPARE` | 无可用游标列且行数 ≤ `full-compare-max-rows` | ✅ | ✅ 每周期全表 upsert |
| `NONE` | 无可用游标列且表过大 | ❌ | ❌ 仅首次全量 |

命名约定按包含匹配、大小写不敏感（`UPDATE_TIME` / `UPDATED_AT` / `LAST_MODIFIED` 等判 `TIMESTAMP`；`CREATE_TIME` / `CREATED_AT` 等判 `IDENTITY`），且列类型必须确为时间类型。项目详情页可为每张表**手动指定游标列**（优先级高于自动识别；指定数字列仍得 `IDENTITY`，检测不到更新），`IDENTITY` / `NONE` 会显著标注原因。

**修改同步不过去的长期解**：给源表加一个真会变的列（MySQL：`ALTER TABLE t ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`）。**补漏**：停止同步后点「重置进度」，下一轮全量重读 upsert 把漏掉的修改带过去 —— 但游标列问题不解决，下一条 UPDATE 仍会漏。

---

## 日志 CDC

轮询的延迟下限是轮询间隔，且看不到 DELETE。四种源库可按项目切换到日志 CDC，切换前工具执行**就绪探测**并逐项列出不满足项；详情页另有 CDC 状态面板（位点、落后秒数、吞吐、跳过计数、最近错误）。

**统一工作流**：① 记位点（全量装载**之前**写入，基线期变更不漏）→ ② 分块全量基线（复用轮询的分块 / 检查点机制）→ ③ 流式回放。一致性契约 **at-least-once**：先提交目标库、再复检租约、最后推进位点，崩溃后从上一位点重放，重叠窗口由幂等 upsert 吸收。

| 引擎 | 源库前提（探测逐项检查） | 位点 |
|---|---|---|
| MySQL binlog | ≥ 5.7（MariaDB 不支持）；`log_bin=ON`、`binlog_format=ROW`、`binlog_row_image=FULL`；`server_id` 不与 Jync 假 id（`server-id-offset` + 项目ID）冲突；账号具备 `REPLICATION SLAVE` + `REPLICATION CLIENT`；binlog 保留 ≥ 24h | binlog 文件 + 偏移（含 GTID 集） |
| Oracle LogMiner | ≥ 11g；`ARCHIVELOG` 模式；库级 `ALTER DATABASE ADD SUPPLEMENTAL LOG DATA`；表级 `ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS`；挖掘权限（`SELECT ANY TRANSACTION` 等）；无 LONG 列；归档保留 ≥ 24h。CDB 下须以公共用户（`C##`）在 CDB$ROOT 挖掘 | SCN |
| PostgreSQL 逻辑解码 | ≥ 9.4；`wal_level=logical`；账号具备 `REPLICATION`；表 `REPLICA IDENTITY` 非 `NOTHING` | LSN |
| SQL Server CDC | ≥ 2012；库级 `sp_cdc_enable_db`；SQL Server Agent 运行中；账号在 `cdc_Admin` / `cdc_reader`；每表已建 capture instance | LSN（20 位 hex） |

**共同边界**：不捕获 DDL（记 WARN 并失效结构缓存，差异大时用「重建基线」）；无主键表仅基线播种、流式阶段跳过；CDC 会复制 DELETE；切换即重新全量，切回轮询保留位点但游标清零先全量对齐。**故障处置**：位点失效（binlog / 归档 / WAL 被清）项目立即置 **ERROR** 等人处置，一键「重建基线」恢复；断线指数退避自动重连（1s 起步翻倍，上限 60s）。**多实例**：同一项目的 CDC 流靠租约互斥，持锁节点宕机后 ≤ 60s 被其他节点接管续传。

---

## 配置项

`application.yml` 的 `sync.*`：

| 键 | 默认 | 说明 |
|---|---|---|
| `poll-interval` | `2000` | 轮询间隔（毫秒） |
| `batch-size` / `fetch-size` | `500` / `1000` | JDBC 批次行数 / 源库读取批量 |
| `chunk-size` | `100000` | 全量每块行数，每块提交 + 写检查点；`0` 关闭分块 |
| `full-load-parallelism` | `1` | 单表全量并行 worker 数（仅单列整数主键表，上限 8） |
| `max-pool-size` / `max-retries` | `5` / `3` | 每库连接池上限 / 连续失败置 ERROR 的次数 |
| `safety-lag-ms` | `1000` | 时间戳水位线回退量，防晚提交事务被跳过 |
| `row-count-audit-interval-ms` | `60000` | 行数审计间隔（毫秒） |
| `full-compare-max-rows` | `20000` | 全表比对的行数上限 |
| `lock-ttl-ms` | `300000` | 同步锁租约时长（毫秒） |
| `sync.cdc.reconnect-backoff-ms` | `1000` | 日志 CDC 断线重连初始退避（毫秒），逐次翻倍 |
| `sync.cdc.reconnect-backoff-max-ms` | `60000` | 重连退避上限（毫秒） |
| `sync.cdc.server-id-offset` | `612000` | 假 replica server id 基数：实际 id = 偏移 + 项目ID |
| `sync.cdc.lock-ttl-ms` | `30000` | CDC 消费者租约时长（毫秒） |
| `sync.cdc.supervise-interval-ms` | `10000` | 各节点巡检抢占 CDC 租约的周期（毫秒） |
| `sync.cdc.connect-timeout-ms` | `10000` | BinaryLogClient 握手超时（毫秒） |
| `sync.cdc.logminer.batch-size` | `10000` | LogMiner `V$LOGMNR_CONTENTS` 单批读取行数 |
| `sync.cdc.logminer.idle-poll-ms` | `1000` | LogMiner 无数据时的轮询间隔（毫秒） |
| `crypto-password` / `crypto-salt` | *(默认值)* | 密码加密密钥与盐（十六进制），**生产必须修改** |
| `ai.enabled` | `true` | 置 `false` 则 AI 菜单与接口一并下线 |

连接密码以 AES-256 加密存储（`enc:` 前缀防重复加密，兼容历史明文）。

---

## AI 辅助转换（可选）

存储过程的过程化控制流无法靠文本替换可靠翻译，`AI 配置` 提供另一条路：模型**起草候选**，人工审阅后落库。边界是硬的：AI 只产候选、永不参与同步（`StructureSyncService` 不调用任何 AI 代码）；候选经确认才写入 `ddlOverrides`；未启用供应商时不产生任何外部请求；`sync.ai.enabled=false` 可整体下线。

供应商支持 OpenAI 兼容与 Anthropic 两种协议，密钥与库密码同样加密存储；探测发一次 `max_tokens: 1` 的**真实请求**（TCP 探活会把密钥错、模型名错、路径差一段全部误报为成功），失败不阻止启用但列表红标。审阅页左右对照源定义与机械转换结果（即不干预时同步真正执行的语句），模型自述的「不确定项」列在 SQL 之上 —— 比 SQL 本身更值得先读。「语法检查」会在**目标库**以 `JYNC_AI_CHECK_<时间戳>` 真建一次对象再删除，仅手动触发并二次确认；通过只证明目标库接受这段 DDL，不证明语义等价。

> ⚠️ 转换会把存储过程源码发往你配置的端点，内网 / 合规部署请接本地推理端点并自行确认代码可外发。

---

## 与市面已有工具的对比

> 口径：各工具官方文档 / 开源版默认部署形态（截至 2026-09），仅针对「开箱即用的异构库持续同步」。

| 维度 | **Jync** | Debezium + Kafka | DataX | Kettle | Navicat / DBeaver 传输 |
|---|---|---|---|---|---|
| 部署形态 | **单个 jar** | Kafka + Connect + ZK | 客户端脚本 | 桌面 + 资源库 | 桌面客户端 |
| 配置方式 | **Web 点选** | YAML/REST + 消费端代码 | JSON 作业 | 图形 ETL | 向导 |
| 持续增量 | 轮询 / Cron + 日志 CDC（MySQL binlog、Oracle LogMiner、PG、SQL Server） | 日志级 | 不适用 | 部分（自建定时） | 不适用 |
| 结构同步 | **自动建表/索引/视图/过程** | 部分（落库自写） | 需预建表 | 部分 | 仅一次性 |
| 异构方言转换 | 类型/函数/引号/分页/过程 | 不支持 | 部分 | 部分（手工） | 部分 |
| 国产数据库 | **达梦/金仓/OceanBase 等 10 种一等公民** | 部分 | 部分 | 部分 | 部分 |
| 源库侵入 | **默认只读零侵入**；CDC 需复制权限仍只读 | 需开 binlog/wal | 只读 | 只读 | 只读 |
| 断点续传 / 幂等 | 游标持久化 + 主键 upsert | offset | 不适用 | 部分 | 不适用 |

**五个差异点**：① 一个 jar 对一套流式基础设施 —— 规模配不上运维成本时差距是决定性的；② 结构同步是一等公民，建表与类型映射不再是你自己的作业；③ 为国产库与信创迁移而生，各方言有专门实现而非「通用 JDBC 也许能连」；④ 默认轮询零侵入，一个只读账号即可；⑤ 解决「持续保持一致」而非「搬一次数」。

**什么时候不该用 Jync**：毫秒级延迟 → Debezium / Flink CDC；单表数亿行一次性初始化 → DataX；复杂 ETL（清洗、聚合、多流 join）→ Kettle / Flink；多主双向复制 → SymmetricDS。

---

## 架构

```
com.qqmu.jync
├── config           i18n、Quartz、Jackson、SecurityConfig、SyncProperties
├── controller       MVC；controller/api 为 REST 端点
├── service
│   ├── connection   DataSourceManager、DriverLoader、DriverShim、连接测试
│   ├── metadata     MetadataReader 各方言实现 + 快照服务
│   ├── monitor      ChangeDetector（结构差异）、CursorStrategyResolver
│   ├── converter    SqlDialect 各实现、类型映射、SQL 体转换
│   ├── sync         SyncEngine、DataSyncService、StructureSyncService、DdlExecutor
│   ├── ai           供应商配置、共享 HTTP 层、候选起草/校验、审阅编排
│   ├── task         Quartz 调度、三层锁、上下文装配、启动恢复
│   └── auth         账号种入、BCrypt 改密、UserDetailsService
├── model / repository / dto / util
```

游标推进与锁获取依赖 `REQUIRES_NEW` 事务语义，因此 `SyncStateWriter` / `SyncLockStore` / `SyncTaskStore` 拆为独立 Bean（同类自调用会绕过 Spring 代理使注解失效）。单实例执行靠三层锁：Quartz `@DisallowConcurrentExecution` → JVM 按项目 `ReentrantLock` → 带租约的数据库锁行（跨重启的那一层）。

---

## 测试

```bash
mvn test
```

1096 个单元测试，重点覆盖：各方言幂等 upsert 与类型上限不变量；SQL 正文改写不碰字面量 / 注释；游标策略解析与降级、Oracle 行级 SCN 探测；游标序列化往返；密码加密与 AI 密钥加密；AI 启用互斥与应答解析（含截断围栏、拒答、空应答）；候选校验在 H2 实库确认不留残留、同名对象内容不变；页面渲染全条件分支且无 `??`；授权规则（访客 POST 全拒、CSRF 缺失即拒）；分页窗口与省略号展开；立即同步补跑合并；分块全量 keyset SQL 形状、检查点生命周期、并行分段不重叠与 long 溢出回退；日志 CDC 探测七项、位点纪律（先数据后位点）、租约互斥与切换序列；README 文档守卫。

真实库集成测试（`*IT`，不进默认套件，需 Docker）：`OracleMigrationHarnessIT`（Oracle 23ai：并行分段全量、kill 后续传、`ROWDEPENDENCIES` 全链路）、`CdcBaselineStreamingIT` / `CdcVolumeAndTypesIT` / `CdcResilienceIT`（MySQL 8 binlog：基线→流式、崩溃重跑、十万行一致、purge 与断线故障）。显式运行加 `-Dapi.version=1.44`。另有端到端脚本（H2 源/目标，20 项断言）覆盖全量、增量、幂等、DDL 传播、并发写入一致与停机补齐。

---

## 已知限制

- **存储过程转换**：机械差异可自动转换；PL/SQL / T-SQL / PL/pgSQL 的控制流无法可靠自动翻译，失败时给具体错误，可在转换审阅页保存手工覆盖（AI 候选仍需人工确认）。
- **语义等价无保证**：游标行为、隐式事务边界、异常控制流、`NULL` 拼接语义不体现在语法上，关键过程务必在目标库实测。
- **语法检查残留**：进程在建 / 删临时对象之间被强杀会残留 `JYNC_AI_CHECK_*`（v1.3.0 之前为 `SYNCTOOL_AI_CHECK_*`），列表页不会自动发现。
- **行删除检测**：无源库审计表时仅对行数低于 `full-compare-max-rows` 的表启用。
- **无主键表**：无法保证幂等，重放可能重复，工具会告警。
- **目标库写入方**：假定目标库仅由本工具写入。
- **延迟**：轮询下限即轮询间隔；MySQL 源可切日志 CDC 拿秒级延迟，其余源库按需扩展日志解析。

---

## 参与贡献

| 渠道 | 账号 |
|------|------|
| QQ | 817094 / 2912167928 |
| QQ 群 | 426669837 |
| 微信 | qqmu66 |

Issue 与 PR 欢迎：[GitHub](https://github.com/vfaner/jync) · [Gitee](https://gitee.com/super_rgh/jync)。觉得有用请点个 Star ⭐

## 许可证

[MIT License](LICENSE)，商业与非商业用途均可。内置第三方 JDBC 驱动各自适用原厂许可证，见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)；GBase 与神通驱动不随本项目分发。
