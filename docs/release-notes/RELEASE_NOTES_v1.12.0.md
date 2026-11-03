# Jync v1.12.0 · 同步前检查、内容校验、诊断包与真实库矩阵

v1.12.0 不加新的数据源类型，把重点放在「同步之前能预判、同步之后能证明、出问题能取证、支持哪些库有据可查」四件事上。

## 新增

- **同步前检查**：项目详情页新增「同步前检查」，在不写入任何数据的前提下排练下一轮。检查源/目标连通性、待执行的 CREATE/ALTER/DROP、目标表缺失或缺列、类型不兼容、Oracle `GENERATED ALWAYS AS IDENTITY`、轮询游标缺失/无索引/无主键，以及 CDC 能力探测。接口为 `GET /api/projects/{id}/precheck`，结果为 ERROR / WARN / OK / SKIP 四组；检查失败仍是 200（结果是数据，不是请求失败）。检查**不执行 DDL、不写快照、不推进游标**。
- **CDC 布局漂移预检**：`BINLOG_CDC` 项目会比较位点里记录的列布局指纹与当前源表结构，消费者停机期间跑过 DDL 会在预检阶段报 ERROR，并提示重建基线，而不是等运行时停车。
- **内容级校验**：增量表同步成功后按主键抽样比对源与目标的行值，能发现「行数相同但内容不同」（更新丢失、截断、字符集或类型转换错误）。默认每 15 分钟、每表 50 行、每周期最多 1 张表、单次 60 秒超时，源行数超过 200 万跳过。发现差异只写 INFO 变更记录并发 `DATA_DRIFT` 告警，**只报告不修复**；同一项目同一张表的相同指纹只记一次。项目进度页新增管理员「检查内容」按钮，可绕过间隔手工校验单表。
- **一键诊断包**：设置页新增管理员「下载诊断包」，生成 `jync-diagnostics-YYYYMMDD-HHmmss.zip`。包内固定为版本与配置形状、连接（不含密码/URL/参数）、项目、进度、CDC 位点（不含签名与游标原值）、最近 200 条变更/审计、日志尾部与 JVM 信息，并附 `manifest.json`（逐条 SHA-256）与说明文件。原始 `application.yml`、`data/`、`jync.key`、H2 文件、`enc:` 密文不会进入包内；自由文本再经一层脱敏，单条目与总解压大小均有上限。每次导出写入 `DIAGNOSTICS_EXPORT` 审计。
- **真实数据库验证矩阵**：新增 [docs/verification-matrix.md](../verification-matrix.md)，按产品列出版本/镜像、驱动、角色、已验证策略、验证级别（自动化容器 / 环境门控 / 手工）与运行命令；新增 `scripts/run-verification-matrix.sh` 本地执行。MariaDB 11.4（源+目标）与 ClickHouse 24.8（仅源）进入自动化容器套件；KingbaseES、YashanDB、Doris、Elasticsearch SQL 保留环境门控测试，无环境变量时干净跳过。

## 优化

- **预检零写入**：预检复用现有的结构变更检测、目标表校验与 CDC 探测，但刻意不调用结构同步、状态写入、快照保存、变更记录/进度保存与审计写入；测试用 Mockito 锁定这一不变量。
- **内容校验的规范比较**：源值在比较前先套用列清洗规则；数字、时间戳、日期、`OffsetDateTime`、`byte[]` 与 Boolean 规范化后再比，`TIMESTAMP` / `DATETIME` 按墙钟时间对齐，避免驱动时区差异造成误报。LOB、JSON、XML、几何等不可比列排除并计数。
- **MariaDB 驱动适配**：MariaDB 自带的 Connector/J 不接受 MySQL 的负 fetch size 流式哨兵，改由产品类型决定读取方式；驱动不再把空转 upsert 当成更新。
- **诊断包脱敏作为第二道防线**：白名单之外，`password|secret|token|authorization|credential = value`、`enc:` 密文、`Bearer`、URL userinfo、敏感查询参数与 PEM 私钥块都会被替换或截断。

## 修复

- **MariaDB 空转误报更新**：MariaDB 驱动默认返回「found rows」，`ON DUPLICATE KEY UPDATE` 即使没有改动也返回 1，安全滞后重放会把每个空转周期都记成一次更新。预设 URL 增加 `useAffectedRows=true` 后，插入/更新/未变分别返回 1/2/0，行数审计与结果计数恢复准确。
- **MariaDB 流式读取**：此前面向 MySQL 家族统一使用 `Integer.MIN_VALUE` 作为 fetch size，MariaDB 驱动会直接报 `invalid fetch size`；现在只对 MySQL Connector/J 使用哨兵，MariaDB 用普通正数流式读取，ETL 与同步共用同一判定。
- **内容校验的日期比较**：修正 `TIMESTAMP` / `DATETIME` 在不同驱动下带时区偏移导致的误判，统一按墙钟时间比较。

## 提醒

- **内容校验是抽样，不是全表哈希。** 它要求表有主键、走增量策略、首次全量已完成，主键列不能有清洗规则；无主键表、`FULL_COMPARE`、ETL 与专属迁移不走这项校验。恒等游标看不到已有行的更新，这类盲区仍需行数审计、全表比对或日志 CDC。详见[已知限制 3.7](../known-limitations.md)。
- **预检不是一次真实同步。** 它只比较源与快照、检查目标形状，不替代一次实际 cycle；CDC 探测可能较慢，页面会显示运行态。
- **诊断包按敏感文件处理。** 白名单与脱敏是尽力而为，日志和变更详情里仍可能出现未知格式的业务敏感文本；不要把诊断包直接贴到公开渠道。
- **验证范围以矩阵为准。** MariaDB 11.4 与 ClickHouse 24.8 在 Docker 下自动验证；Doris、Elasticsearch SQL、KingbaseES、YashanDB 的环境门控测试本次未运行，不能据此宣称这些版本已全部验证。ClickHouse JDBC 仍不随包分发。
- 本次无破坏性变更，替换 `jync.jar` 重启即可；升级前备份 `./data` 与 `application.yml`。
