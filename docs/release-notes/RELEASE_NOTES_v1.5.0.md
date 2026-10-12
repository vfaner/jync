# Jync v1.5.0

本版主题是**日志 CDC(MySQL binlog)捕获策略**:MySQL 源库可按项目从「轮询游标列」切换到「解析 binlog 流式回放」—— Jync 伪装成一台只读从库,INSERT / UPDATE / DELETE 全捕获,秒级延迟,严格按 binlog 顺序回放;全程**不建触发器、不写源库**,对 binlog 只读。切换前有逐项就绪探测,运行中有状态面板,致命故障有 ERROR 帽与一键重建基线,多实例部署按租约互斥、自动接管。全部新行为都有真实 MySQL 容器集成测试背书。

## 本次更新

### 日志 CDC:探测 → 基线 → 流式 → 接管 → 优雅停机

- **就绪探测(七项检查)**:版本(MySQL ≥ 5.7)、`log_bin` 开启、`binlog_format=ROW`、`binlog_row_image=FULL`、`server_id` 非 0 且不与 Jync 假 id 冲突、`REPLICATION SLAVE`/`REPLICATION CLIENT` 权限、binlog 保留 ≥ 24 小时 —— 逐项给出满足/不满足与原因,连接测试页与项目策略卡片都会执行
- **基线**:启动时先把当前 binlog 位点(文件 + 偏移,开启 GTID 时含 GTID 集)持久化到 `cdc_position` 表,**再**执行 keyset 分块全量装载(复用 v1.4.0 的分块 / 检查点机制);位点先于全量写入,基线期间源库的变更已被续读点覆盖,不会漏;基线中途崩溃则整体重跑,不留半份数据
- **流式回放**:基线完成后从记下的位点消费 binlog,逐事务回放到目标库(与轮询模式共用同一套方言 upsert / 按主键删除写引擎);一致性契约 **at-least-once**:先提交数据、再复检租约、最后推进位点,任何一步崩溃都从上一位点重放,重叠窗口由幂等 upsert 吸收
- **故障分类与退避重连**:binlog 被清理、`server_id` 冲突等致命故障 → 项目立即置 **ERROR**,不再无限重试;网络断线 / 源库重启 → 指数退避自动重连(1 s 起步逐次翻倍,上限 60 s),连接被证实稳定驻留 ≥ 30 s 后才重置退避,避免热循环打爆源库
- **多实例接管**:CDC 消费者按**租约互斥**,同一项目同一时刻至多一个节点消费;持锁节点宕机后租约在 ≤ 30 s 内过期,其余节点巡检(默认 10 s)抢到租约并从持久化位点续传,总接管 ≤ 60 s,接缝由 at-least-once 重放兜底,不丢事件
- **优雅停机**:应用关闭时在等待窗口内停止消费并释放租约,重启后从持久化位点续传

### 三策略卡片、状态面板与一键重建基线

- 项目详情页的捕获策略以**三张卡片**呈现(轮询 / 全量比对 / 日志 CDC);CDC 卡片仅在源库为 MySQL 且探测全部通过时标注「推荐」;切换有二次确认(明示「切换即重新全量」与「CDC 会复制 DELETE」两大语义差异),操作记入审计日志
- **CDC 状态面板**实时展示:当前位点、落后秒数、吞吐、已应用行数、各类跳过计数(DDL、不支持对象、零日期强制转换)与最近错误
- ERROR 或结构分叉后一键**重建基线**:停止消费 → 清空位点 → 重新分块全量
- 连接测试页同步展示逐项探测结果,不满足项直接给出原因

### 真实环境修复(由 MySQL 容器集成测试首跑抓出)

- **TIME 值改为按源库字面文本全程传递**(`JdbcRowMapper` / `DataSyncService`):负值与 ≥ 24 小时的合法 MySQL 时长型 TIME(如 `838:59:59`、`-10:30:00`)此前会被 JDBC 驱动直接抛错(整表基线失败)或按钟表时间静默截断,现在原样复制。**该修复作用于共享写引擎,存量轮询链路同样受影响**,不只 CDC
- CDC 侧同步修复:binlog 二进制 JSON → 规范文本、BIT 列 `BitSet` → 大端字节、有符号 TIME2 wire 值解码(负时长不再损坏)、1970 年前 TIMESTAMP 微秒向下取整对齐、binlog 监听器异常不再被吞(经 handler 引用与强制断连上浮)
- purge 错误措辞分类补全 MySQL 8.0 GTID 形态("source purged required binary logs… missing transactions"),位点失效能正确落 ERROR 帽而非无限重试

### 测试与性能

- 单元测试增至 **847** 个(默认套件,Docker 无关),全绿
- 新增三个 MySQL 8.0 真实容器集成测试类(Testcontainers;`*IT` 不进默认套件,无 Docker 自动跳过),**31 个用例全绿**。本发布实测(2026-10-11,Apple Silicon,Docker 29.4.0,mysql:8.0 = 8.0.46,JDK 17):

| IT 类 | 覆盖 | 用例 | 类耗时 |
|---|---|---|---|
| `CdcBaselineStreamingIT` | 基线 → 流式一致性;基线中途崩溃整体重跑 | 2 | 1.9 s |
| `CdcVolumeAndTypesIT` | 10 万行基线 + 1 万条混合 DML 收敛 + 25 项单行类型往返(基线腿 + 流式腿双断言) | 26 | 33.7 s |
| `CdcResilienceIT` | 15 s 冻结零丢失;purge → ERROR → 一键重建基线;双实例租约接管 | 3 | 31.5 s |

- 关键实测数字(测试内 nanoTime 计时):
  - **10 万行分块基线 9.9 s**(种子写入 0.6 s)
  - **1 万条混合 DML(INSERT / UPDATE / 主键迁移 / DELETE,100 个事务)下发 2.2 s,从下发开始到两端一致 2.5 s**,收敛后全表校验和一致
  - **15 s 容器冻结窗零丢失**(解冻后追加 100 行全部到达,最终 301 行一致),解冻后追平 **0.26 s**
  - **故障接管 4.0 s**(含测试强制的 4 s 等待窗,即真实接管为亚秒级)≪ 60 s 上限
  - **binlog 被 purge → ERROR 帽落地 0.24 s;一键重建基线恢复(清位点 → 基线完成 → 56 行一致)0.8 s**
  - 类型矩阵单项往返 0.48–0.92 s(25 项全绿,含 TIME 极端值、零日期、JSON、BIT、四种字符集)

## 配置

新增六个 `sync.cdc.*` 配置项(其余复用既有 `sync.*`,完整表见 README):

| 键 | 默认 | 说明 |
|---|---|---|
| `sync.cdc.reconnect-backoff-ms` | `1000` | 日志 CDC 断线重连的初始退避(毫秒),逐次翻倍 |
| `sync.cdc.reconnect-backoff-max-ms` | `60000` | 重连退避上限(毫秒) |
| `sync.cdc.server-id-offset` | `612000` | 假 replica server id 基数:实际 id = 偏移 + 项目ID;与源库 `server_id` 冲突时调大 |
| `sync.cdc.lock-ttl-ms` | `30000` | CDC 消费者租约时长(毫秒);持锁节点宕机后租约在此窗口内过期,供其他节点接管 |
| `sync.cdc.supervise-interval-ms` | `10000` | 各节点巡检并抢占 CDC 租约的周期(毫秒) |
| `sync.cdc.connect-timeout-ms` | `10000` | BinaryLogClient 握手超时(毫秒) |

## 升级

换 jar、重启即可:元数据库(`ddl-auto: update`)自动新增 `cdc_position` 表;**存量项目一律默认 POLLING,行为零变化** —— 日志 CDC 是按项目显式切换的 opt-in,不切就与 v1.4.0 完全一致。

**一处共享引擎行为变化(轮询链路同样受影响)**:TIME 值改为按源库字面文本端到端传递(`JdbcRowMapper` / `DataSyncService`)。此前负值与 ≥ 24 h 的 TIME 在读取路径会被驱动抛错(整表同步失败)或写入时静默截断成钟表时间;本版起原样复制。若下游曾依赖旧的(错误)截断结果,升级后请留意此类列的取值变化。

## 兼容性

- `sync_config` JSON 新增 `captureStrategy` 字段:**缺省 / null 反序列化为 `POLLING`**,v1.4.0 及更早保存的项目配置语义不变;派生的「有效策略」不参与持久化,JSON 里只存显式选择的值
- MariaDB / TiDB / OceanBase / GBase 虽属 MySQL 家族,但 binlog 方言各有差异,**不在 CDC 支持范围**(探测直接判不支持);日志 CDC **仅 MySQL 源库**,其余源库维持轮询 / 全量比对策略

## 已知限制

- **不捕获 DDL**:binlog 中的 DDL 记 WARN 日志后跳过(状态面板有「跳过 DDL」计数),同时失效缓存的表结构;源库改表后需**人工对齐**目标库结构,差异大到无法对齐时用「重建基线」重新全量
- **无主键表仅播种一次**:基线阶段一次性播种,流式阶段跳过其行事件,并在 CDC 状态面板**逐表标注**「无主键表,仅基线播种」—— 没有主键就无法幂等回放
- binlog 保留期若短于停机窗口,位点可能被源库清理(项目置 ERROR);用「重建基线」恢复,并评估调大 `binlog_expire_logs_seconds`

## 完整文档

[README(中文)](https://github.com/vfaner/jync/blob/main/README.md) · [README (English)](https://github.com/vfaner/jync/blob/main/README_EN.md)
