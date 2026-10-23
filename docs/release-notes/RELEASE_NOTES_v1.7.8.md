# Jync v1.7.8

大字段彻底修复：大文本与大二进制在任意两种受支持数据库之间都能正确同步，读取、绑定与 CDC 缓冲不再把整个值压在堆内存里。同时补齐厂商专属类型的映射与取值归一化，跨方言建表不再产出非法类型名。默认配置保持 1.7.7 的可见行为，新增开关均可关闭回退。

## 修复

- **Oracle 目标绑定超过 32767 字节即失败**：增量轮询、CDC 回放、续传到非空表以及非空目标表的全量都走 `MERGE INTO ... USING (SELECT ? ... FROM DUAL)`，该投影对 `String` 的绑定上限是 32767 个 UTF-8 字节（约 10923 个汉字），超过即 `ORA-01461`。现在 Oracle 目标的大文本/大二进制统一经服务端临时 `CLOB` / `BLOB` 绑定（`createClob` + `setCharacterStream` + `setClob`），`MERGE` 与 `INSERT`、批处理与单行执行都适用；临时 LOB 在批次提交、回滚与异常路径全部释放。
- **空串写入 `NOT NULL CLOB` 报 `ORA-01400`**：Oracle 把 `''` 绑成 `NULL`。`sync.lob.oracle-empty-string-as-empty-clob=true`（默认）时空串绑定为空 `CLOB`，保留「非空的空值」；`VARCHAR2` / `CHAR` 目标仍按 Oracle 语义存 `NULL`。
- **大字段整块读入堆内存**：读取路径此前一律 `getString` / `getBytes`，全量一张大表就能耗尽堆。声明为大字段的列现在走流式读取，不超过 `inline-max-bytes` 的值内联，更大的值按块消费；JDBC 批次按 `batch-max-bytes` 提交，一次性流永不进入批次。
- **CDC 大事务缓冲占满堆**：事务缓冲中超过 `cdc-spill-threshold-bytes` 的值先落到 `spill-dir`，提交、回滚、DDL、断流与停止时删除；落盘总量受 `spill-max-bytes` 约束，异常退出留下的文件在下次启动按 `spill-retention-hours` 清理（只删 `jync-lob-*` 前缀）。
- **PostgreSQL / openGauss 源端流式读取无效**：JDBC 驱动在 autocommit 下忽略 fetch size 并缓冲整个结果集。现在源端读取期间保持一个只读事务，`fetch size` 真正生效；会话可逆，失败回滚并恢复原 autocommit。`sync.lob.enable-source-streaming=false` 可关闭。
- **厂商专属类型跨方言产出非法 DDL**：`java.sql.Types` 之外的厂商码此前原样透传源类型名。现在先归一化再映射：Oracle `BINARY_FLOAT` / `BINARY_DOUBLE` / `TIMESTAMP WITH TIME ZONE` / `INTERVAL` / `ROWID` / `XMLTYPE` / `SDO_GEOMETRY`，SQL Server `UNIQUEIDENTIFIER` / `MONEY` / `DATETIMEOFFSET` / `SQL_VARIANT` / 几何，DB2 `DECFLOAT` / `XML` / `ROWID`，MySQL `JSON` / `ENUM` / `SET` / `GEOMETRY` 均有显式目标类型；同族保留原生类型，跨族回退到可移植类型并记录日志，绝不再透传未知类型名。
- **厂商类型取值未归一化**：Oracle `XMLTYPE` / `INTERVAL` / `ROWID` → `String`、`BINARY_FLOAT` → `Float`；SQL Server `UNIQUEIDENTIFIER` → `String`、`MONEY` → `BigDecimal`、`DATETIMEOFFSET` → 带时区时间、几何 → `byte[]`；DB2 `DECFLOAT` → `BigDecimal`、`XML` → `String`；MySQL `ENUM` / `SET` / `JSON` → `String`。全量、增量与三种 CDC 读取共用同一套归一化。
- **LogMiner 行外 LOB 静默跳过**：`SQL_REDO` 对行外存储的 LOB 没有取值，回查会破坏事务顺序。此前只记一条 DEBUG。现在计入 `skippedMissingLob` 与按表计数并暴露到 CDC 状态面板，按表限流告警（含表名、跳过数、配置键与补救建议）；`sync.cdc.logminer.missing-lob-policy=fail` 可改为在第一条此类变更处停住且不推进位点。
- **SQL Server 源多列大字段在流式读取下提前断流**：mssql-jdbc 在读取同一行的下一列时会关闭上一列已打开的流，默认流式下 SQL Server 源表中只要有两个大字段列（例如 `NVARCHAR(MAX)` 后面跟着 `VARBINARY(MAX)`），基线就会以 `Failed to fill a temporary CLOB` / `Stream closed` 失败。SQL Server 源现在在读取大值所在列时即落盘（复用 `spill-dir` 与 `spill-max-bytes`），不再跨列持有驱动流；全量与增量共用该路径。
- **临时 LOB 与落盘文件泄漏**：所有成功、回滚、锁丢失与异常路径都会释放临时 LOB 并删除本次会话的落盘文件；sink 失败保留缓冲与文件以便重试，由事务边界释放。
- **主键 / 游标 / 清洗列被误当大字段流式处理**：这些列永远物化，清洗规则仍生效；超过 `force-inline-max-bytes` 时明确报错并指明表、列与配置键，不静默截断。
- **模拟 upsert 与 CDC 重放重复绑定**：同一值需要绑定多次时，一次性流会在第一次绑定前报错，可重放值（内存或落盘）正常执行。

## 安全加固

- **未认证请求可以耗尽内存**：登录失败计数按「用户名 + 客户端 IP」记账，此前用户名不截断、计数表也没有上限——未登录的攻击者每次提交一个随机（甚至上兆字节的）用户名，就能让进程永久保留这些条目。现在用户名在入 key 前截断到 64 字符，计数表上限 10000 条，超限时先回收过期条目再淘汰任意条目。
- **转发头信任范围过宽**：`X-Forwarded-For` / `X-Real-IP` 此前对任何私网直连都采信。内网部署中客户端本身就是私网地址，于是任何同网段用户都能逐次伪造来源 IP，使 5 次失败锁定形同虚设，并把伪造地址写进审计。现在只信任回环地址与 `app.security.trusted-proxies` 中显式列出的代理（支持 IPv4/IPv6 CIDR），配置写错会在启动时直接失败而不是静默退化。
- **只读账号可以触发源库探测**：`GET /api/projects/{id}/cdc-probe` 会以管理员配置的凭据连接源库并执行探测查询，此前未列入管理员专属 GET，viewer 也能调用；现已收紧为管理员。
- **依赖版本补齐**：Spring Boot 2.7.18 是 2.7 线最后一个 OSS 版本，其管理版本停在 2023 年。本次覆盖到同主版本线上后续发布的安全修复：Tomcat `9.0.83 → 9.0.123`、Spring Framework `5.3.31 → 5.3.39`、Spring Security `5.7.11 → 5.7.14`（分别覆盖 CVE-2024-24549 / CVE-2024-23672、CVE-2024-22243 / CVE-2024-22259、CVE-2024-22257）。
- **默认口令账号可先改密再使用**：`admin` / `view` 仍使用初始口令 `123456` 时，登录后直接落在首页，改密弹窗自动打开且无法关闭；其他页面与 API 一律拒绝（页面重定向回首页，API 返回 403 JSON），首页看板内容也不下发。新口令不能继续使用默认值。此前只能靠页面上的提醒，用户不改也能照常操作。
- **既有安装的内置密钥仍是公开值**：数据库密码与 AI 密钥此前用源码里公开的默认密钥加密，等同明文。现在升级后首次启动会自动生成随机密钥，在一个事务里重加密全部已存密钥，提交成功后才落盘为 `<store>.key`；中途崩溃会在下次启动时按 `key.pending` 续做，无法解密的旧值则放弃迁移并保留原行为（不阻断启动）；提交后落盘失败时进程改用 pending 密钥继续运行并保留该文件，下次启动补完落盘，避免此后再写入的凭据与库中密文对不上。显式配置 `sync.crypto-password` / `sync.crypto-salt` 的部署不受影响。
- **表单绑定可覆盖不可编辑字段**：数据库连接与 AI 提供商的新增/编辑接口此前直接绑定 JPA 实体，手工构造的 POST 可以写入页面上不存在或不可编辑的字段（如已存密文、启用状态、上次测试结果）。现在改为专用表单对象，只接收页面实际提交的字段。

## 新功能

- **同步对象面板支持目标表改名**：每张表一行「源表 → 目标表」，中间虚线箭头连接，目标表名输入框留空即与源表同名（placeholder 显示源表名），输入中的空格自动去掉（含全角空格）。未勾选的表不保存映射；重复目标表与运行中禁止修改映射的校验保持不变。窄屏下改为源表在上、目标表在下。
- **大字段与流式设置卡片**：系统信息页新增「大字段与流式」，展示流式开关、内联阈值、CDC 落盘阈值、单批字节预算、源端流式、落盘目录与容量、清理保留期与 LogMiner 缺失 LOB 策略，中英文同步。
- **CDC 状态面板新增缺失 LOB 计数**：`skippedMissingLob` 全局计数与 `missingLobByTable` 按表计数，可与 `skippedUnsupported` 区分——前者表示 LogMiner 结构上无法还原，后者表示语句不被解析。
- **全量并行支持所有有主键的键形态**：此前只有单列整数主键能切成互斥区间；现在复合键（整数前导列按 MIN/MAX 算术切分）与字符串/UUID、小数、文本前导复合键（用两级窗口函数从源端采样桶首键作为边界）都能并行全量。无窗口函数的库（如 MySQL 5.7）运行期自动回退顺序分块；断点续传会校验区间边界，边界随并行度或数据范围变化时整表重载而不是跳过数据。
- **空表 INSERT-first 推广到 MySQL / PostgreSQL**：首次全量写入空目标表时，MySQL 用普通 `INSERT` 代替 `INSERT ... ON DUPLICATE KEY UPDATE`，PostgreSQL 代替 `ON CONFLICT`（Oracle 的 INSERT 代替 MERGE 保持不变）。目标表仍是一次性探测，非空表继续走幂等 upsert。
- **无主键表的全量可按页续传**：目标表为空且存在可排序列时，首次全量改为按可排序列 `ORDER BY` + `OFFSET/LIMIT` 分页，每页提交后写检查点。重启**从目标表行数续读**而不是从检查点续读——崩溃恰好发生在「页已提交、检查点未写」之间时，检查点会落后一页，按检查点续读会把该页整页重抄一遍，而无主键表没有唯一键可以吸收重复。目标非空、全部列都是 LOB，或源库无法可靠表达非零 OFFSET（SQL Server 2012 之前、DB2 11.1 之前）时仍走原来的单条流式读取，并继续显示「无法续传」标记。

## 限制（升级前务必阅读）

- **不是零拷贝**：Oracle `MERGE` 必须使用服务端临时 LOB；模拟 upsert 与 CDC 重放要求同一值可重放；PostgreSQL 逻辑解码与 SQL Server CDC 仍由驱动/函数整行物化；MySQL 单个值受源库 `max_allowed_packet` 限制。v1.7.8 做到的是内存有界并可落盘——上限是「单值 × 读取批量」，不再是整表。
- **SQL Server 源的大字段需要落盘空间**：读到的超阈值大值会立即写入 `spill-dir`，该分区必须能容纳当前读取窗口内的值（容量受 `spill-max-bytes` 约束）。
- **更新前镜像与删除镜像不落盘**：它们只用于主键比较与删除，事务缓冲期间保留在堆内存中。MySQL 默认 `binlog_row_image=FULL`、SQL Server 的 op=3 / op=2 行以及 PostgreSQL `REPLICA IDENTITY FULL` 下这些镜像带整行数据，所以大字段表上密集的删除或移动主键的更新仍会占用「缓冲事件数 × 单行大小」的内存，不受 `cdc-spill-threshold-bytes` 约束；这类表应保持事务短小。
- **PostgreSQL 源库的大字段需要更小的读取批量**：pgjdbc 在 `getCharacterStream` 内部把文本解码为 `String`（约 UTF-8 字节数的 2-3 倍），带 fetch size 时还会持有最多该行数的原始字节。数 MB 单值时请调低 `full-load-fetch-size`（1-10），并保证堆至少能容纳最大单值的 3 倍。
- **LogMiner 行外 LOB 不可还原**：只能跳过或失败，二者都显式可见。补救方式是重建基线，或该表改用轮询。
- **Oracle `VARCHAR2` / `CHAR` 的空串仍是 `NULL`**，只有 `CLOB` 目标保留空串。
- **厂商原语语义降级**：`ENUM` / `SET` → `VARCHAR`、`XMLTYPE` / `XML` → `CLOB`、几何 → `WKB` 二进制、`SQL_VARIANT` 丢失类型、`TIMESTAMP WITH LOCAL TIME ZONE` 依赖源会话时区。
- **PostgreSQL 长读事务代价**：`enable-source-streaming` 开启时，读取期间源库 vacuum 被推迟；只读从库或短连接环境可关闭。
- **DB2 无本地镜像**：厂商类型映射仅有单元测试覆盖，实机验证需用户环境。
- **无主键表的 OFFSET 续传按位置定位行**：装载期间源库的并发插入/删除可能使后续页的窗口偏移，导致个别行漏读或重复。有主键的表按主键值定位，不受影响；对写入频繁的无主键表，建议在业务低峰执行首次全量，或事后核对行数。
- **无主键续传只在本次装载自己留下空表时启用**：目标表已有数据时无法区分「上次装载了一半」和「外部写入的数据」，此时仍走单条流式读取。

## 兼容性说明

- **类型映射变化可能改变既有目标 DDL**：以前未知厂商码会透传源类型名，现在映射为可移植类型。升级后首次结构同步前建议先做结构预检（或先对空库试跑），确认目标 DDL 符合预期。
- 所有新增开关默认值都以「行为与 1.7.7 一致或更安全」为准，且均可关闭：`sync.lob.streaming-enabled=false` 回到整块读取，`sync.lob.enable-source-streaming=false` 关闭源端事务，`sync.cdc.logminer.missing-lob-policy=skip`（默认）保持原跳过行为。
- 落盘目录默认 `${java.io.tmpdir}/jync-lob`，需要确保该分区有足够空间；容量由 `spill-max-bytes` 约束。
- Oracle `MERGE` 大值绑定的行为变化只在目标为 Oracle 时生效，其他方言仍走流式绑定。
- 配置项 `oracle-initial-load-insert-first` 更名 `initial-load-insert-first`；旧键仍可绑定，无需改 YAML。该开关现在同时作用于 MySQL 与 PostgreSQL 目标。
- **部署了跨机反向代理的用户必须配置 `app.security.trusted-proxies`**（或环境变量 `JYN_TRUSTED_PROXIES`，逗号分隔的地址或 CIDR）。默认只信任回环地址；未配置时，经代理转发的请求一律按代理 IP 计算登录锁定与审计来源，多个用户会共用同一个锁定计数。同机（回环）代理无需配置。
- **升级后首次启动会重加密已存凭据**：使用内置默认密钥的既有安装会在启动时生成 `./data/jync.key`（或与数据库同名的 `.key`）并重加密数据库密码与 AI 密钥。该文件是这些凭据的唯一密钥，必须随 `./data` 一起备份；丢失后已存密码无法恢复，需要重新录入。`jync.key.pending` 是迁移中的暂存文件，`jync.key.lock` 只是加锁用的空文件，都不需要备份。同一数据目录上同时启动多个实例时，只有一个会执行迁移，其余实例在迁移期间会拒绝启动并提示稍后重试——否则它会一直用公开默认密钥加密此后保存的凭据，导致别的进程读不出来；迁移完成后重新启动即可。

## 测试

- 1962 个单测：1961 通过，1 个按环境条件跳过；`*IT` 需 Docker 另跑。
- 新增/扩充的大字段集成测试：Oracle → MySQL（600 KB CLOB + 1 MB BLOB 的基线与增量 upsert）、SQL Server → Oracle（`NVARCHAR(MAX)` / `VARBINARY(MAX)` 的基线与增量 MERGE）、MySQL → Oracle（100k 汉字增量 MERGE、5 MB CLOB/BLOB、`NOT NULL CLOB` 空串、厂商类型）、Oracle → openGauss（行外 LOB 跳过）、MySQL CDC 事务落盘（2 MB 行、提交后无残留）、可选 PostgreSQL → Oracle 60 行 × 约 5 MB 大字段（300 MB 源数据，`-Plob-memory-it` 在 96 MB 堆下完成基线与增量 MERGE）。

## 升级

- 替换 `jync.jar` 重启即可，无需手工迁移。使用内置默认密钥的既有安装会在首次启动时自动重加密已存凭据，并生成需要随 `./data` 一起备份的 `.key` 文件。
- 升级前备份 `./data` 与 `application.yml`。
- 容器部署将镜像 tag 更新为 `jync:1.7.8`（Docker Compose 与 `deploy/k8s/` 清单已同步）。
