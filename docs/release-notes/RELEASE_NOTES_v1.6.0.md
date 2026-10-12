# Jync v1.6.0

本版主题是**日志 CDC(Oracle LogMiner)捕获策略**:Oracle 源库可按项目从「轮询游标列」切换到「解析 LogMiner 流式回放」—— 基于 Oracle 内置的 LogMiner 引擎挖掘 redo 日志,INSERT / UPDATE / DELETE 全捕获,秒级延迟。与 MySQL binlog CDC 共用同一套租约互斥、状态面板、ERROR 帽与一键重建基线机制。

## 本次更新

### Oracle LogMiner CDC:探测 → 基线 → 流式

- **就绪探测(七项检查)**:源库可达、`ARCHIVELOG` 模式、库级最小补充日志、表级 `ALL COLUMNS` 补充日志、挖掘权限(`SELECT ANY TRANSACTION` + `EXECUTE ON DBMS_LOGMNR` + `SELECT ON V$*`)、无 LONG 列、归档保留 ≥ 24 小时 —— 逐项给出满足/不满足与原因
- **基线**:启动时读取 `V$DATABASE.CURRENT_SCN` 作为基线锚点,持久化到 `cdc_position` 表的 `scn` 字段(新增列),**再**执行 keyset 分块全量装载;位点先于全量写入,基线期间源库的变更已被后续挖掘覆盖
- **流式回放**:基线完成后从记下的 SCN 开始挖掘 `V$LOGMNR_CONTENTS`,按事务提交 SCN(CSCN)逐事务回放到目标库;SQL_REDO 解析器支持 Oracle 方言(NUMBER/VARCHAR2/DATE/TIMESTAMP/RAW/CLOB/BLOB 字面量、`("OWNER"."T"("COL",...))` 无空格语法、UPDATE SET 仅变更列 + WHERE 完整旧镜像 + ROWID、NLS 形 TO_TIMESTAMP)
- **一致性契约**:与 MySQL binlog CDC 相同的 at-least-once —— 先提交数据、再复检租约、最后推进 SCN,崩溃后从上一个 SCN 重放,重叠窗口由幂等 upsert 吸收

### 运维接口与 UI

- 项目详情页新增 **LogMiner CDC 策略卡**(仅 Oracle 源库渲染),与 MySQL binlog 卡并列;探测通过时标注「推荐」
- CDC 状态面板新增 **SCN 位点**渲染(`SCN xxx`),与 binlog 位点(file:pos / GTID)并存
- 切换确认框同时覆盖两种日志策略(BINLOG_CDC / LOGMINER_CDC)
- 连接测试页对 Oracle 源库附加七项 LogMiner 就绪检查
- `cdc_position` 表新增 `scn` 列(自动 DDL,存量行 scn 为 null)

### 结构性变更(披露)

- **ManagedCdcConsumer 接口抽取**:原 `CdcConsumer`(binlog)与新增 `LogMinerCdcConsumer` 统一实现 `ManagedCdcConsumer` 接口(`Runnable + requestStop()`);`CdcConsumerManager` 按策略类型分派消费者实例。**该抽取为结构性变更,行为零差异** —— MySQL binlog 链路经全套 31 例容器 IT 回归证明不受影响
- 通用门由 `== BINLOG_CDC` 改为 `isLogCdc()`:同步引导、调度、对账、引擎等五处泛化,覆盖两种日志策略

### CDB 模式限制(实测)

在 CDB 环境(12c+ 容器数据库)中,LogMiner 的 `ADD_LOGFILE` / `START_LOGMNR` 只能在 CDB$ROOT 中以公共用户(`C##` 前缀)执行。同步账号需为该公共用户并具备挖掘权限;PDB-local 账号会收到 `ORA-65040`。v1.6.0 当前实现使用单一 source config 连接,**CDB 模式下的端到端流式通路需后续版本支持分离的挖掘/数据连接**;基线全量加载与 SCN 位点记录已在 23ai Free 容器上验证通过。

### 测试

- 单元测试增至 **902** 个(默认套件,Docker 无关),全绿
- 新增 Oracle 23ai Free 真实容器集成测试(Testcontainers),**3 个用例全绿**:

| IT 类 | 覆盖 | 用例 |
|---|---|---|
| `LogMinerBaselineStreamingIT` | LOGMINER_CDC 基线全量 + SCN 位点 + baselineDone | 1 |
| `LogMinerVolumeAndTypesIT` | 全类型表结构同步 + 数据播种 | 1 |
| `LogMinerResilienceIT` | 伪造残缺位点 → 整体重跑基线 | 1 |

- MySQL binlog IT **31 例零回归**(接口抽取容器级证明)
- 流式挖掘通路由 `LogMinerPathSpikeIT` 单独实证(CDB$ROOT 公共用户挖掘、SRC_CON_ID 过滤、SQL_REDO 方言录制)

## 配置

新增两个 `sync.cdc.logminer.*` 配置项:

| 键 | 默认 | 说明 |
|---|---|---|
| `sync.cdc.logminer.batch-size` | `10000` | V$LOGMNR_CONTENTS 单批读取行数 |
| `sync.cdc.logminer.idle-poll-ms` | `1000` | 无数据时轮询间隔(毫秒) |

## 升级

换 jar、重启即可:元数据库(`ddl-auto: update`)自动为 `cdc_position` 表新增 `scn` 列;**存量项目一律默认 POLLING,行为零变化** —— Oracle LogMiner CDC 是按项目显式切换的 opt-in,与 MySQL binlog CDC 互不干扰。

## 兼容性

- `sync_config` JSON 新增 `LOGMINER_CDC` 枚举值;`captureStrategy` 缺省 / null 仍反序列化为 `POLLING`
- `cdc_position` 表新增 `scn` 列(BigDecimal,可空);binlog 项目的 scn 始终为 null,不影响既有位点语义
- Oracle 版本支持:11g+(11g 走 `EXECUTE_CATALOG_ROLE` / `SELECT_CATALOG_ROLE` 分支,12c+ 走 `LOGMINING` 角色 + 显式视图权限)

## 已知限制

- **CDB 模式端到端流式**:当前生产代码使用单一 source config 连接;CDB 环境中挖掘需 root 连接、基线读取需 PDB 连接,后续版本支持分离连接配置
- **Oracle FLOAT 列**:StructureSyncService 自动创建目标表时,FLOAT 列类型映射存在兼容性问题(JDBC 类型码不匹配),建议手动对齐目标列类型
- **不捕获 DDL**:LogMiner 输出的 DDL 记 WARN 日志后跳过
- **LONG 列 / 超行内 LOB**:此类表会被跳过并计数

## 完整文档

[README(中文)](https://github.com/vfaner/jync/blob/main/README.md) · [README (English)](https://github.com/vfaner/jync/blob/main/README_EN.md)
