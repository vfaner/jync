# Jync v1.13.0

## 新增

- **Redis 作为实时同步的目标**：项目页的「目标类型」可选 Redis，源端 CDC 持续把每一行的变化投影到缓存。Redis 是目标端专属能力，不是新的 `DatabaseType`，不出现在源端下拉、ETL 引擎列表与数据库能力矩阵里。
- **Redis 目标连接**：连接管理页新增「Redis 目标」标签，独立维护 host / port / database / ACL 用户名 / 密码 / TLS / Cluster 等参数；密码只存加密字段、页面不回显，「测试连接」执行 `PING`，集群目标额外检查 `CLUSTER INFO`。列表、表单与写操作仅管理员，保存与删除写审计（`REDIS_TARGET_SAVE` / `REDIS_TARGET_DELETE`）。Redis 页面与表单独立于数据库连接页面，`DatabaseConfig` 相关代码零改动。
- **行级缓存投影**：键为 `<前缀><表名>:<主键…>`，前缀默认 `jync:<项目ID>:`，同一 Redis 上不同项目的投影选项互不影响。值形态二选一：
  - **JSON（默认）**：每个 key 一个 JSON 对象，就是整行业务数据；`DECIMAL`/`NUMERIC` 写成字符串避免精度丢失，时间写 ISO-8601，不嵌入任何 Jync 元数据。
  - **HASH**：每个 key 一个 Hash，field = 列名，`NULL` 列为字段缺失。
- **基线、回放与重建**：启用项目时先做分块基线（只投影有主键的表），完成后再开始流式回放；项目详情页提供「重建」按钮，可选择先按本项目前缀清扫（`SCAN` + `UNLINK`，只动本前缀，绝不 `KEYS` / `FLUSHDB` / `FLUSHALL`）再重新基线。
- **Redis 预检**：同步前检查在 Redis 项目上只跑 Redis 项：连通性、TLS、集群槽与 databaseIndex、`maxmemory-policy`、键前缀合法性与冲突、选表主键、基线状态、LOB 列、布局漂移；只读，不写任何数据。
- **源端强约束**：Redis 目标只能配日志 CDC，`POLLING` / `FULL_COMPARE` 在页面不出现、服务端也拒绝（`error.project.redis.cdcOnly`）；没有日志 CDC 的源类型不能选 Redis。结构、索引、视图、存储过程同步对 Redis 强制关闭。

## 优化

- **目标写入接缝**：五种 CDC 消费者的目标写入段提取为 `CdcTargetTransaction`，JDBC 实现只是原样搬移，Redis 实现独立接入。存量项目（`targetKind` 为空）在任何路径上仍按 JDBC 处理，无需数据迁移。
- **故障语义与数据库目标一致**：Redis 不可达或命令失败按目标写入失败处理——位点不推进、消费者退避重连、持续失败进入 ERROR 帽，恢复后从原位点重放；`SET`/`HSET`/`DEL` 幂等，重放收敛（at-least-once）。
- **每个 CDC 事务一次管道**：事务内的命令先缓冲、`commit()` 时一次性管道执行并逐条检查回复；不使用 `MULTI/EXEC`，避免集群同槽限制。缓冲超过 `maxCommandsPerTransaction` 时先执行一批再继续。
- **可观测**：Redis 项目复用 CDC 状态面板的位点、延迟、变更计数与最近错误；被跳过的行（超 `maxValueBytes`、无主键、无法编码）按事务汇总成一条 WARN，不刷屏、不停机。

## 修复

- **MySQL 5.7 源端全量刷屏报错**：5.7 没有窗口函数，按主键采样切分的查询是语法错误，此前每张表、每次全量都打一条 `Source cannot split … (You have an error in your SQL syntax …)` 再退回顺序分块；行为本来是对的（数据不丢，只是不并行），但日志被刷满。现在按服务器实际版本在采样前判定，5.7 直接走顺序分块，**每个项目只提示一次**，内容校验同样直接标 `SKIPPED` 而不发查询。
- **按连接自动区分 MySQL 5.7 / 8.0**：构建同步上下文时从服务器元数据读取主版本（按连接配置与修改时间缓存，连不上不缓存、下一轮重试），分别下发给源与目标方言——两端可以是不同版本。5.7（以及 MariaDB 10.2 之前）不启用窗口函数采样，新建表时也不写 `DATE DEFAULT (CURRENT_DATE)` 这类 8.0.13+ 才接受的表达式默认值，改为丢弃该默认值，而不是让 `CREATE TABLE` 失败。版本读不到（含 Doris、TiDB 等 MySQL 协议产品）时保持原行为：先尝试、被拒再回退。

## 已知边界

- Redis 侧的键驱逐、`FLUSHDB`、重启丢数据 Jync 感知不到；读不到即回源，或执行一次重建。
- 基线只写「当前存在的行」：源端在基线之前已删除的行会在 Redis 留下孤儿键，用 `ttlSeconds` 或带清扫的重建消除。
- HASH 形态删列/改名不会自动删除旧字段，检测到布局漂移会停车并提示重建；JSON 是整行镜像，不在此列。
- 不支持 Sentinel；集群模式不做 hash tag，同表键不保证同槽。无主键表不投影。
- 详见 [已知限制](known-limitations.md) 第 12 节与 [配置](configuration.md) 的 Redis 缓存目标一节。

---

数据库连接、同步项目、ETL 与专属迁移的存量配置不需要改动；升级只需替换 `jync.jar` 并重启。升级前建议照常备份 `./data` 与 `application.yml`。
