# Jync v2.4.1

## 新增

- **TDengine 轮询源**：新增 TDengine 3.x 预设（预设数 35 种），经官方驱动 3.10.0 的 WebSocket 接口（6041 端口）读取，需自备 `taos-jdbcdriver-3.10.0-dist.jar`（应用不内置）。元数据走 information_schema 与 `DESCRIBE`，超级表进入同步清单、子表不重复列举；支持 `ts` + 声明主键或声明主键 + TAG 的复合主键分块全量、时间戳 / 数值游标轮询与按主键比对的删除检测。驱动把 `VARCHAR` 与 `BINARY` 都报成 `jdbcType 12` 且 `getObject` 返回 `byte[]`，同步读取、ETL 取数与表预览按文本处理，文本主键不再被渲染成十六进制；`BIGINT UNSIGNED` 走 `BigDecimal`。3.3.6 拒绝 `TIMESTAMP` 列的 `MAX`/`MIN`，时间戳游标的水位改用 `LAST(列)`，整型游标仍用 `MAX`（`LAST(id)` 是最大时间戳那一行的 id，不是最大 id）。无游标列的表行数估算走共享 TTL 缓存，超级表不再每轮 `COUNT(*)` 全扫；缺索引提示改为「或改用主键前导列」（TDengine 没有普通列二级索引）。TDengine 不提供日志级 CDC，只作轮询源。
- **TDengine ETL 目标**：按取数 SQL 写入已存在的普通表或子表，也可由「前置 SQL」建表（建出超级表会在写入前提示）；目标表缺失或为超级表时，都在覆盖模式的 `DELETE` 与任何行写入之前报错。Jync 不自动建表（生成的 `NOT NULL` + `PRIMARY KEY` 会被 TDengine 拒绝），不支持 UPSERT 与超级表写入；目标列是 `TIMESTAMP` 时，源端墙上时间文本（MySQL `DATETIME` / `TIME`）先解析为 `java.time` 再绑定（驱动拒绝文本直接入列，`0x2303`）。

## 优化

- **驱动提示**：需要自备驱动的 10 个预设，提示行与输入框占位符改为厂商实际发布的包名和实测版本（如 `taos-jdbcdriver-3.10.0-dist.jar`、`ngdbc-2.30.12.jar`），驱动类占位符改为该预设的真实类名；切换类型时即时同步，填写路径后提示行仍常驻。

## 修复

- **布尔列默认值**：MySQL `TINYINT(1)` → PostgreSQL 系 `BOOLEAN` 的建表语句把默认值 0 / 1 改写为 `FALSE` / `TRUE`，其他数字丢弃默认值，避免目标库拒绝 `BOOLEAN DEFAULT 0`；openGauss、GaussDB 与金仓同方言族一并生效，非布尔目标保持数字默认值不变。
- **Oracle 改列**：`MODIFY` 不再追加 `NULL` / `NOT NULL` 子句，已可空的列不再报 `ORA-01451`（此前只避开了 `ORA-01442`）；达梦、崖山复用同一方言一并修复。
- **CDC 布尔绑定**：MySQL `TINYINT(1)` 列在 binlog 里解出 `Integer` 0 / 1，写入 `BOOLEAN` 目标时改按布尔绑定，PostgreSQL 系目标不再报「column is of type boolean but expression is of type integer」。

## 已知边界

- TDengine 只支持 3.x、只经 WebSocket（6041）；只作轮询源与 ETL 目标，没有 CDC，轮询窗口看不到物理删除；开启「同步删除」且在比对上限内的表可按主键比对删除。
- TDengine 的「范围覆盖」只接受带明确时间窗的 `DELETE`，范围条件须用 `ts` 限定；ETL 目标没有事务，失败重跑可能留下部分数据。
- **不要把 `DECIMAL` 列用作 TDengine 的 ETL 目标列**：服务端 3.3.6.0 配驱动 3.10.0 会把参数按文本发给服务端、按二进制解码，写入错误数值且不报错，Jync 侧没有可用的绑定方式绕过。
- 验证基线为 TDengine 3.3.6 社区版 + 驱动 3.10.0，其他版本未验证。

## 升级

替换 `jync.jar` 并重启即可。本版没有元数据库结构变更；既有连接、同步任务、ETL、迁移与 AI 配置无需迁移。新建 TDengine 连接时请准备官方 `-dist` 驱动 jar。运行环境仍需 Java 17 或更高版本。
