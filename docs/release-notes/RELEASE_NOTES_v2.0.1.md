# Jync v2.0.1

## 升级内容

- **CDC 流内 DDL 不再让任务停摆**：源库在同步运行期间加列、改结构时，旧版本只能跳过该 DDL，随后携带新列的行事件在目标端报 `Unknown column`，整条 CDC 停摆。本版把这条路径做成不停摆的闭环：
  - **流内裁剪**：目标库尚缺的列从 INSERT / UPDATE 语句中剔除，行事件继续应用；项目详情的 CDC 状态新增「裁剪次数（本次运行，列值未同步）」与「目标库缺源库列（已自愈）」两项计数。被裁剪的列值在补齐任务完成后由重放补回。
  - **DDL 待办**：跳过的 DDL 在【变更日志】登记为待办，分「待处理 / DDL 已执行，待补齐 / 已处理」三种状态。页面可**在目标库执行**该 DDL、**重试补齐**缺失数据，或对齐后**关闭待办**。
  - **执行前校验**：执行对话框只允许修改 COMMENT 备注与方言细节（标识符引号、大小写）；表名、列名、字段类型与子句顺序都会与源 DDL 比对，不一致直接拒绝。库名以目标库为准自动改写，表名映射同样应用到语句。只接受表与索引类语句（CREATE / ALTER / DROP / TRUNCATE TABLE、CREATE / DROP INDEX），其余类型提示手工处理，避免把未改写的原始语句打到目标库。
  - **数据补齐**：执行 DDL 后重读受影响表，以 upsert 覆盖到目标库；只补行值，不清空目标表，也不删除目标端多出的行。执行与补齐只对管理员开放（POST + CSRF），目标连接由变更日志所属项目解析，不接受请求参数指定连接。
  - **重启与去重**：待办身份改为「项目 + binlog 位置 + 语句」（LogMiner 等无位点的来源按语句文本），重启后不会重复登记，已关闭的任务也不会因重启发回；清空变更日志保留未完成的 DDL 待办。
  - **噪声抑制**：GRANT / REVOKE / ANALYZE / OPTIMIZE / 账户与库管理语句不再登记为结构变更；DROP DATABASE / SCHEMA 不生成可执行待办。其他 schema 或未同步表上的 DDL 在 XA prepared 期间直接忽略，不再以 `XA_ORDERING` 永久停摆，也不再清空已缓冲的行。
  - **告警**：新增「CDC DDL 被跳过」告警分类，按事件推送（不发送恢复通知），详情含本次与累计条数，并指向变更日志与 ERROR 日志。
  - **解析边角**：支持 CREATE / DROP INDEX（含 USING）、CREATE / DROP / TRUNCATE TABLE 的 schema 与表名改写；解析前剥掉注释、`USE db;` 与 MySQL / MariaDB 的版本化注释（`/*!NNNNN ... */`、`/*M!NNNNN ... */`，其内容按真实 SQL 解包而不是丢弃）；ALTER 子句切分识别反斜杠转义，`COMMENT 'it\'s, ok'` 不再被拆成两半；子句方言按源库决定。
- **ETL 新增「主键更新」（UPSERT）写入模式**：任务表单新增「主键列」，主键已存在则更新、其余插入。MySQL 协议族用单条 `INSERT ... ON DUPLICATE KEY UPDATE`（Doris 虽走 MySQL 协议但拒绝该语法，自动改用通用路径）；其他目标用「先 UPDATE，未命中再查存在性、仍不存在才 INSERT」的模拟，并发撞上唯一键时只在键确实已被目标持有才吞掉冲突。预检提示键列在结果与目标表中的存在性；配置包导出 / 导入携带键列。
- **修复 MySQL 元数据库的长文本列（error 1406）**：Hibernate 7 对未声明长度的 `@Lob` 字段在 MySQL 上按最小一档建表，导致 `project.sync_config`、`etl_task.source_sql`、`metadata_snapshot.snapshot_json`、`cdc_position.gtid_set` 等列只有 TINYTEXT（255 字节），首次导入配置包即报 `Data too long for column`。现在 30 个同类字段显式声明 `LONGTEXT`，新库直接建宽；**已升级过的旧 MySQL 元数据库**由启动期的 `MetadataTextColumnWidener` 把本应用自己表里的 TINYTEXT 列改为 LONGTEXT（仅 MySQL / MariaDB，幂等，单列失败只记 WARN 不阻断启动）。
- **ETL 其他修复**：
  - pre-sql 改为在目标表创建之后、写入模式清理之前执行，文档示例中的 `DELETE FROM 目标表` 首次运行即可生效；存在断点续跑时不再重放 pre-sql，避免删掉首次运行已写入的行。
  - 缺表判定在 SQLState / 错误码之外补充各厂商报文匹配，覆盖 ClickHouse、Hive、Impala、HANA；`Database does not exist` 这类报文不再被误判为缺表而触发建表，权限与锁错误也不会；ClickHouse 的 60 不按 MySQL 死锁处理。
  - 原生 upsert 与唯一索引覆盖判定排除前缀索引与函数索引，避免把「不覆盖键」的目标当成可 upsert 而在写入时产生重复行。
  - 取数 SQL 以 `--` 行注释结尾时，预检零行探测与断点续跑包装在 `)` 前换行，注释不再吞掉包装。
  - UPSERT 键值含 NULL 时按列名报错，不再静默重复插入。
- **测试基线**：全量 **5487** 个测试通过（1 个按环境跳过）。

## 已知边界

- DDL 待办只覆盖表与索引语句；视图、存储过程、函数、触发器等其他对象仍不在待办内，需人工在目标库对齐。
- 「重试补齐」按受影响表整表重读并 upsert，目标端多出的行不会被删除；大表可能耗时较久。DDL 已删列或收紧类型造成的历史数据损失无法由补齐恢复，只能从备份或源库历史数据中处理。
- 「主键更新」要求目标表在配置的键列上有主键或唯一索引；由 Jync 自动创建的目标表会带上主键。
- MySQL 元数据库的自动加宽只处理本应用通过实体映射创建的表；显式声明过长度的 TEXT 列保持原样。PostgreSQL / Oracle / H2 元数据库不受 TINYTEXT 问题影响。
- 内嵌 H2 仍固定为 **2.1.214**，不要自行换成 2.2 及以上；后续升级 H2 时会另行提供导出 / 导入迁移工具。
- `*IT` 集成测试（Oracle / MySQL / PostgreSQL / ClickHouse / MariaDB / Redis / MongoDB 真库）不在 `mvn test` 默认范围，需显式运行。

---

升级只需替换 `jync.jar` 并重启；连接、同步项目、ETL 任务（含键列留空）、专属迁移与 TLS 证书配置均不需要迁移。运行环境仍需 Java 17 或更高版本。
