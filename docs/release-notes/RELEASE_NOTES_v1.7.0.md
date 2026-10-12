# Jync v1.7.0

本版主题是**日志 CDC 双扩展(PostgreSQL logical slot + SQL Server CDC)**:继 v1.5.0 的 MySQL binlog 与 v1.6.0 的 Oracle LogMiner 之后,Jync 支持的日志类源库扩到四种 —— MySQL、Oracle、PostgreSQL、SQL Server 全部可按项目从「轮询游标列」切换到「流式回放」,INSERT / UPDATE / DELETE 全捕获,秒级延迟。两种新策略共用同一套租约互斥、状态面板、ERROR 帽与一键重建基线机制。

## 本次更新

### PostgreSQL 逻辑解码 CDC

- **就绪探测(五项检查)**:PostgreSQL 源可达、`wal_level=logical`、当前用户具备 `REPLICATION` 角色(或 superuser)、`test_decoding` 输出插件可用(探测时自动创建并丢弃临时复制槽)、所选表 `REPLICA IDENTITY` 可用(非 `NOTHING` —— 否则 UPDATE/DELETE 拿不到旧镜像,无法定位行)
- **基线**:启动时读 `pg_current_wal_lsn()` 作为锚点,持久化到 `cdc_position.lsn` 列(新增),**再**执行 keyset 分块全量装载;基线期间源库的变更已被后续 slot 读取覆盖
- **流式回放**:通过 JDBC 调 `pg_logical_slot_get_changes('jync_<projectId>', NULL, 1000, 'include-xids', '1')` 批量拉取,`test_decoding` 文本协议解析器处理 BEGIN/COMMIT 事务边界、单引号字符串与 `''` 转义、`NULL` 列、UPDATE 的 `old key-` 旧镜像跳过
- **slot 生命周期**:首次连接时自动创建 `jync_<projectId>` 复制槽,**停止时不 drop**(否则 WAL 会被永久保留且失去水位);slot 回收由 DBA 手动执行 `pg_drop_replication_slot`
- **一致性契约**:与 binlog / LogMiner 相同的 at-least-once —— 先提交数据、再复检租约、最后推进 LSN,重叠窗口由幂等 upsert 吸收

### SQL Server CDC

- **就绪探测(五项检查)**:SQL Server 源可达、`sys.databases.is_cdc_enabled = 1`(库级 CDC 已启用)、SQL Server Agent 运行中(捕获 / 清理作业依赖)、当前用户在 `cdc_Admin` / `cdc_reader` 角色(或 sysadmin)、所选表在 `cdc.change_tables` 中已存在 capture instance
- **基线**:启动时读 `sys.fn_cdc_get_max_lsn()` 作为锚点,持久化到 `cdc_position.lsn`(hex 字符串,`CONVERT(varchar(32), ..., 1)` 格式),**再**执行全量装载
- **流式回放**:从 `cdc.fn_cdc_get_all_changes_<capture_instance>(@from_lsn, @to_lsn, 'all update old')` 按 LSN 区间拉取,`__$operation` 分派 —— `1` = DELETE、`2/4` = INSERT/UPDATE(走 upsert)、`3` = UPDATE 前镜像(upsert 语义不需要,跳过)
- **事务边界**:`__$start_lsn` 相等即同一事务,按 LSN 分组 flush;空批 backoff 1 秒(CDC 靠 SQL Server Agent 异步捕获,轮询过密无收益)

### 运维接口与 UI

- 项目详情页策略选择区新增 **PG 逻辑解码 CDC** 与 **SQL Server CDC** 两张策略卡(分别在 PostgreSQL / SQL Server 源库时渲染),与 MySQL / Oracle 卡并列;探测通过时标注「推荐」
- CDC 状态面板新增 **LSN 位点** 渲染(`LSN 0/167D3F8` 或 hex 字符串),与 binlog 位点(file:pos / GTID)、Oracle SCN 共存
- 连接测试页对 PostgreSQL / SQL Server 源库自动附加对应就绪检查
- `cdc_position` 表新增 `lsn VARCHAR(64)` 列(自动 DDL 迁移,存量行 lsn 为 null)
- 项目保存时按源类型严格校验策略:`PG_LOGICAL_CDC` 仅允许 `DatabaseType.POSTGRESQL`,`SQLSERVER_CDC` 仅允许 `DatabaseType.SQLSERVER`;不匹配时拒绝保存并返回 `error.project.cdc.not.postgres` / `error.project.cdc.not.sqlserver`

### 配置

新增两组 tuning 参数:

| 键 | 默认 | 说明 |
|---|---|---|
| `sync.cdc.pg.batch-size` | `1000` | `pg_logical_slot_get_changes` 单批行数 |
| `sync.cdc.pg.idle-poll-ms` | `500` | 空批轮询间隔(毫秒) |
| `sync.cdc.pg.slot-prefix` | `jync_` | 每项目复制槽前缀,最终名 `jync_<projectId>` |
| `sync.cdc.mssql.batch-size` | `1000` | `cdc.fn_cdc_get_all_changes_*` 单批行数 |
| `sync.cdc.mssql.idle-poll-ms` | `1000` | 空批轮询间隔(毫秒) |

### 测试

- 单元测试增至 **913** 个(默认套件,Docker 无关),全绿
- 新增 `PgTestDecodingParserTest` 7 个用例:BEGIN/COMMIT 边界、INSERT/UPDATE/DELETE 行事件、`NULL` 列、单引号 `''` 转义、坏行降级
- 既有 906 例零回归 —— `CaptureStrategy.isLogCdc()` 扩至四值后,SyncEngine / SyncScheduler / ScheduleReconciler / SyncBootstrap 通用门自动覆盖

## 前置条件

### PostgreSQL 侧需 DBA 预先配置

```sql
-- postgresql.conf
wal_level = logical          -- 需重启
max_replication_slots = 10    -- 每项目一个槽

-- 同步账号
CREATE ROLE jync WITH LOGIN REPLICATION PASSWORD '<pwd>';
GRANT CONNECT ON DATABASE app TO jync;
GRANT USAGE ON SCHEMA public TO jync;

-- 所选表 REPLICA IDENTITY(默认即 DEFAULT/PK 即可,无 PK 时改 FULL)
ALTER TABLE public.orders REPLICA IDENTITY DEFAULT;
```

### SQL Server 侧需 DBA 预先配置

```sql
-- 库级 CDC
EXEC sys.sp_cdc_enable_db;

-- 每表启用
EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name    = N'orders',
    @role_name      = N'cdc_Admin',
    @capture_instance = N'dbo_orders';

-- 同步账号
CREATE USER jync WITH PASSWORD = '<pwd>';
ALTER ROLE cdc_Admin ADD MEMBER jync;
```

## 升级

换 jar、重启即可:元数据库(`ddl-auto: update`)自动为 `cdc_position` 表新增 `lsn` 列;**存量项目一律维持原策略,行为零变化** —— PG / SQL Server CDC 是按项目显式切换的 opt-in。

## 兼容性

- `sync_config` JSON 新增 `PG_LOGICAL_CDC` / `SQLSERVER_CDC` 枚举值;`captureStrategy` 缺省 / null 仍反序列化为 `POLLING`
- `cdc_position` 表新增 `lsn` 列(VARCHAR(64),可空);binlog / LogMiner 项目的 lsn 始终为 null,不影响既有位点语义
- PostgreSQL 版本支持:9.4+(logical decoding 引入;`test_decoding` 官方插件);推荐 12+
- SQL Server 版本支持:2012+(CDC 引入);推荐 2017+(Linux 上 SQL Server Agent 已正式支持)

## 已知限制

- **PG 复制槽生命周期**:consumer 停止时不 drop slot,WAL 会被保留直到 DBA 手动 drop;长期停跑的项目请 DBA 执行 `SELECT pg_drop_replication_slot('jync_<projectId>')`
- **SQL Server Agent 依赖**:SQL Server Agent 不运行则变更表不更新,CDC 空转;Agent 停止 → 项目不会 ERROR,但会无延迟积压
- **PG DDL / 表不存在**:consumer 遇到未识别表名时降级为 unsupported-table 计数,不会中断流
- **SQL Server capture instance 命名**:consumer 从 `cdc.change_tables` 自动发现所有捕获实例,按项目表白名单过滤;若 DBA 自定义了非默认命名,仍能正确匹配

## 完整文档

[README(中文)](https://github.com/vfaner/jync/blob/main/README.md) · [README (English)](https://github.com/vfaner/jync/blob/main/README_EN.md)
