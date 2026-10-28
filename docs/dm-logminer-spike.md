# 达梦 DBMS_LOGMNR 实测记录

这份记录是一次**可行性验证**，不是功能文档：用于判断 DM / 崖山要不要走独立的日志 CDC 引擎，以及如果走，适配层要补什么。所有结论都来自一次真实 DM8 实例（Docker `xuxuclassmate/dameng:latest`，`DB Version: 0x7000c`）。

> **2026-10-28 更正。** 本记录第一版的核心结论（「`SQL_REDO` 不能当 SQL 用，列名被改写成 `COL n`，只能走 `MINE_VALUE`」）**是错的**。原因是挖掘时没有带 `DICT_FROM_ONLINE_CATALOG`，DM 拿不到字典，只能把列名降级成 `COL n` 渲染。带上该选项后 DM 输出标准 SQL，与 Oracle 的 `SQL_REDO` 几乎同形。下面全部是重测后的结果；第一版的错误结论已删除，不是并存。

## 1. 环境前提（全部在 DBA 侧，与程序无关）

| 前提 | 做法 | 说明 |
|---|---|---|
| 归档模式 | `dmarch.ini` 里配 `ARCH_TYPE=LOCAL` + `ARCH_DEST`，`dm.ini` 里 `ARCH_INI=1`，重启实例，再 `ALTER SYSTEM SWITCH LOGFILE` | 只改 `ARCH_INI` 不建 `dmarch.ini` 时，`V$ARCHIVED_LOG` 一直是空的 |
| 逻辑日志 | **`RLOG_APPEND_LOGIC=2`**（重启生效） | **这一条最容易踩坑，见下** |
| 挖掘账号 | 需要 `DBMS_LOGMNR` 执行权限 + 读 redo/归档的权限 | 和 Oracle 一样，是**授予账号**，不是改程序 |
| 保留期 | 归档文件要覆盖最长停机窗口 | 归档被清理 = 位点失效 = 只能重建基线 |
| **归档周期** | 定时 `ALTER SYSTEM SWITCH LOGFILE`（DM 作业，建议 ≤ 1 分钟） | **见 1.2，这一条不配，CDC 延迟以小时计** |

代价与 Oracle 一致：redo 体积变大（行镜像进 redo），归档要留够。

### 1.2 只能挖归档文件，当前联机 redo 挖不了 —— 延迟由归档周期决定

`DBMS_LOGMNR.ADD_LOGFILE('/opt/dmdbms/data/DAMENG/DAMENG01.log')` 直接报错：

```
[-4546]:File open failure
-4546: DBMS_LOGMNR.ADD_LOGFILE line 19 .
```

也就是说 **DM 的 LogMiner 只能读归档文件**（`V$ARCHIVED_LOG.NAME`），当前正在写的 redo 不在可挖范围内。这与 Oracle 有本质差别：Oracle 能把 `V$LOG.STATUS='CURRENT'` 的联机成员加进去，所以"刚提交的变更"下一次轮询就能看到；DM 必须等这份 redo 写满（默认 `RLOG_SIZE` 256 MB）或被显式切换。

`V$DM_INI` 里**没有**按时间自动切换归档的参数（`ARCH*` / `RLOG*` 全量查过）。所以 DBA 侧必须自己挂一个 DM 作业跑 `ALTER SYSTEM SWITCH LOGFILE`，间隔就是 CDC 的延迟上界。这一点要写进部署前提和能力探测：探测到最新归档文件很旧时，直接把「归档周期过长」作为提示项报出来，而不是让用户以为 CDC 坏了。

### 1.1 `RLOG_APPEND_LOGIC` 必须是 2，不能是 1

| 取值 | 实测 `SQL_REDO` 的 UPDATE | 能否支撑回放 |
|---|---|---|
| `1` | `UPDATE "SYSDBA"."LM_T2" SET "C_STR" = 'changed', "C_DATE" = DATE'2026-12-31' WHERE "ID" = 2;` | **不能**。WHERE 只有主键，未改动的列没有旧镜像 |
| `2` | `UPDATE "SYSDBA"."LM_T2" SET "C_STR" = 'changed', "C_DATE" = DATE'2026-12-31' WHERE "ID" = 2 AND "C_DEC" = 34.5 AND "C_STR" = 'O''Brien, "x"' AND "C_DATE" = DATE'2026-03-04' AND "C_TS" = TIMESTAMP'2026-03-04 05:06:07.123456' AND "C_NULL" IS NULL;` | 能。全列旧镜像，形态与 Oracle 开 `SUPPLEMENTAL LOG DATA (ALL) COLUMNS` 后完全一致 |

`1` 的后果不是"少一点信息"：Jync 的 upsert 会绑定**全部**列，缺列会被写成 NULL——现有 Oracle 解析器正是为此整条拒绝（`refuseIfColumnsAreMissing`）。所以 `RLOG_APPEND_LOGIC=2` 是硬前提，地位等同 Oracle 的表级 `(ALL) COLUMNS` 补充日志。

设置方式：`CALL SP_SET_PARA_VALUE(1,'RLOG_APPEND_LOGIC',2);` 然后**重启实例**（`V$DM_INI` 里确认生效）。

## 2. 接口：与 Oracle 同名，语义大部分一致

`SYS.DBMS_LOGMNR` 包存在，公开过程：

`ADD_LOGFILE` / `START_LOGMNR` / `END_LOGMNR` / `REMOVE_LOGFILE` / `COLUMN_PRESENT` / `MINE_VALUE`

`V$LOGMNR_CONTENTS` 有 61 个列，列名与 Oracle 完全一致（`SCN`、`CSCN`、`TIMESTAMP`、`OPERATION`、`SEG_OWNER`、`SEG_NAME`、`TABLE_NAME`、`SQL_REDO`、`SQL_UNDO`、`ROW_ID`、`ROLLBACK`…）。

### 2.1 `START_LOGMNR` 的选项

`CALL DBMS_LOGMNR.START_LOGMNR(options=>2+16);`

| 位 | 常量 | 作用 | 实测 |
|---|---|---|---|
| 2 | `COMMITTED_DATA_ONLY` | 只返回已提交事务的行 | 实测有效：回滚掉的 INSERT 不出现在结果里 |
| 16 | `DICT_FROM_ONLINE_CATALOG` | 用在线字典还原列名 | **必开**，否则列名降级为 `COL n` |

两个都要开。

具名参数 `startScn` / `endScn` 在 DM8 上**可用**（实测 `START_LOGMNR(startScn => 0, endScn => 999999999999, options => 2+16)` 正常返回），所以一轮挖掘的范围可以直接在引擎侧收窄，不必只靠游标的 `WHERE`。

`V$LOGMNR_CONTENTS` 的列解析在**未开启挖掘会话**时会先报 `[-2844] must be invoked before selecting`——这是运行期守卫，不是列不存在；开启会话后，Oracle 形状的整条 SELECT（含 `XID`、`TIMESTAMP`、`SCN`、`CSCN`、`0 AS SRC_CON_ID`）实测可跑通并返回行。DM 没有 `SRC_CON_ID`，故一律用 11g 形态。

### 2.2 会话作用域

LogMiner 状态绑定在**发起挖掘的那个数据库会话**上：另开一个 `disql` 连接调 `END_LOGMNR` 会报 `[-2846] no LogMiner session is currently active`。适配层必须保证 `ADD_LOGFILE` → `START_LOGMNR` → 读游标 → `END_LOGMNR` 全程在同一条 `Connection` 上，且异常路径也要 `END_LOGMNR`（现有 Oracle 的 `LogMinerSession` 已经是这个形状）。

### 2.3 归档清单：`V$ARCHIVED_LOG` 形状兼容，但 `DEST_ID` 恒为 NULL

DM 的 `V$ARCHIVED_LOG` 有 Oracle 发现逻辑要用的全部列：`NAME`、`THREAD#`、`SEQUENCE#`、`FIRST_CHANGE#`、`NEXT_CHANGE#`、`DEST_ID`。实测 12 行归档，`THREAD#` 全为 `0`，`SEQUENCE#` 递增。

**但 `DEST_ID` 恒为 NULL**，Oracle 那句 `WHERE DEST_ID = 1` 在 DM 上会过滤掉全部行——照抄会得到"没有可用归档日志"的假错误。适配层要为 DM 去掉这个谓词。

联机 redo 的路径在 `V$RLOGFILE(GROUP_ID, FILE_ID, PATH, RLOG_SIZE, …)`，用来判断"当前 redo 有多大 / 多久没切换"，但**不能**加进 `ADD_LOGFILE`（见 1.2）。归档模式用 `V$DATABASE.ARCH_MODE = 'Y'` 判断。

### 2.3.1 续跑边界：`MAX(NEXT_CHANGE#)` 是"尚未可挖的第一位"，比较符要 `>=`

DM 没有 `CURRENT_SCN`，一轮挖掘的上界只能是 `SELECT MAX(NEXT_CHANGE#) FROM V$ARCHIVED_LOG`（空归档时该值为 NULL，视为"还没有上界"）。

这个值**不是**"已挖到的最后一个 SCN"，而是"最新归档文件的下一位置"。下一轮从它续跑时，下一个文件的第一条提交**可以正好落在该 SCN 上**，所以游标过滤必须是 `CSCN >= ?`，照抄 Oracle 的 `CSCN > ?` 会永久丢掉这一条。

实测还发现：`ALTER SYSTEM SWITCH LOGFILE` 之后，归档文件在 `V$ARCHIVED_LOG` 里出现时**还在继续被写入**（同一个 `NAME` 的 `NEXT_CHANGE#` 从 43956 长到 43988）。同一份文件先挖到 0 行、一分钟后挖到 4 行。所以：

- 挖到一半的文件是常态，不是异常；
- `>= 上界` 的续跑配合"文件仍在 `NEXT_CHANGE# > startScn` 的发现集合里"，正好把后来追加的行接上；
- 反过来，如果按"挖过的文件就跳过"来设计，就会丢掉同一文件后段的行。

### 2.3.2 `RS_ID` 不单调，不能当语句序号

四条 DML 在同一事务里提交，`CSCN` 全为 43985，`RS_ID` 依次是 `11, 16, 20, 7`——**不是递增的**。所以：

- `ORDER BY CSCN, RS_ID` 只能保证同一 CSCN 内的稳定输出，不能用来给语句排序；
- 续行合并（Oracle 用 `RS_ID` 拼超长 `SQL_REDO`）在 DM 上暂时无害，因为实测每条语句的 `RS_ID` 各不相同、且 DM 未见把一条 `SQL_REDO` 拆成多行；但**不能**把 `RS_ID` 当作跨事务唯一键；
- 排序与去重的真正依据仍是 `CSCN` + 消费者自己的状态。

### 2.4 列行为对照

| 列 | Oracle 行为 | DM8 实测 | 影响 |
|---|---|---|---|
| `SEG_NAME` | 表名 | **全为 NULL** | 表名只能从 `SQL_REDO` 的 `"OWNER"."TABLE"` 前缀取，或 `DATA_OBJ#` → `ALL_OBJECTS.OBJECT_ID` 反查 |
| `SEG_OWNER` | schema 名 | schema 名（`SYSDBA`，开字典后是真名） | 可用 |
| `DATA_OBJ#` | 对象号 | `1059` 等，可经 `ALL_OBJECTS.OBJECT_ID` 反查 | `WHERE DATA_OBJ# = ?` **能**正常过滤 |
| `SQL_REDO` | 标准 SQL | **标准 SQL**，大写关键字，表名 `"OWNER"."TABLE"`，无 `ROWID` | 现有解析器可直接吃，见第 3 节 |
| `SQL_UNDO` | 反向 SQL | **全为 NULL** | 不能用于回滚/补全旧镜像 |
| `ROW_ID` | 行标识 | INSERT 有值（递增），UPDATE / DELETE 为 NULL | 不能用 `ROW_ID` 匹配前后镜像 |
| `OPERATION` | `INSERT`/`UPDATE`/`DELETE`/`DDL` | 还有 **`START`** 与 **`COMMIT`** 两种事务标记行 | 必须按 `OPERATION` 白名单过滤，否则标记行（`SQL_REDO` 为 NULL）会进解析器 |
| `CSCN` | 提交 SCN | 同一事务所有行相同；`COMMIT` 标记行的 `SCN = CSCN` | 位点取 `CSCN`，与 Oracle 引擎一致 |
| `REDO_VALUE` | 句柄 | 有值（`2`/`8`…），但 `MINE_VALUE(8,'C1')` 这种裸值调用报 `[-6803]` | 见 2.4 |

`WHERE` 谓词在 `V$LOGMNR_CONTENTS` 上工作正常（`WHERE 1=1`、`WHERE OPERATION='INSERT'`、`WHERE DATA_OBJ#=1061` 都实测可用），可以按表收窄。

### 2.5 `COLUMN_PRESENT` / `MINE_VALUE` 的列名要三段式

签名是 `(redo_value BIGINT, column_name VARCHAR)`。列名**必须写成 `OWNER.TABLE.COLUMN`**：

```sql
-- 报 [-6803] Invalid parameter value / DBMS_LOGMNR.COLUMN_PRESENT line 31
select dbms_logmnr.column_present(redo_value,'C1') from v$logmnr_contents;
select dbms_logmnr.column_present(8,'C1')          from v$logmnr_contents;

-- 正常返回
select dbms_logmnr.column_present(redo_value,'SYSDBA.LM_T4.C1') from v$logmnr_contents;
```

`MINE_VALUE` 的列名同样要求三段式，且返回值与 `SQL_REDO` 里的字面量**逐字节相同**（含下面第 4 节的精度截断），所以它**不是** `SQL_REDO` 之外的第二条信息通路，没有理由为它写第二套解值逻辑。

## 3. 解析器兼容性：`SqlRedoParser` 只差 4 个小口子

DM（`options => 2+16`）与 Oracle 的 `SQL_REDO` 形态逐项对照：

| 特征 | Oracle | DM8 | 现有解析器 |
|---|---|---|---|
| 关键字大小写 | 小写 `insert` / `update` | **大写** `INSERT` / `UPDATE` | ✅ `indexOfWord` 大小写无关 |
| 表名形态 | `"SRC"."T"(` 无空格 | 同 | ✅ |
| 数值字面量 | 带引号 `'1'` | **裸值** `2`、`34.5`、`-12.34` | ✅ `looksLikeNumber` 分支 |
| 字符串 | `'O''Brien'` | 同（`''` 转义） | ✅ |
| `NULL` | `NULL` | 同 | ✅ |
| WHERE 里的 NULL | `"C" IS NULL` | 同 | ✅ `nullPredicateColumn` |
| `DATE` | `TO_DATE('...','syyyy-mm-dd hh24:mi:ss')` | **`DATE'2026-03-04'`** | ❌ 需要新增 ANSI 字面量分支 |
| `TIMESTAMP` | `TO_TIMESTAMP('...','yyyy-mm-dd hh24:mi:ss.ff')` | **`TIMESTAMP'2026-03-04 05:06:07.123456'`** | ❌ 同上 |
| `TIMESTAMP WITH TIME ZONE` | `TO_TIMESTAMP_TZ(...)` | **`TIMESTAMP'2026-03-04 05:06:07.123456 +08:00'`** | ❌ 同上（带时区偏移的同一个前缀） |
| `BLOB` / `VARBINARY` | `HEXTORAW('DEAD')` | **`0xDEADBEEF`** | ❌ 需要新增 `0x` 分支 |
| 行外 LOB | 列直接从 redo 里消失 | **`"C" = OUT_CLOB` / `OUT_BLOB`** | ❌ 必须映射到"缺列"路径（见 3.1） |
| `ROWID` | WHERE 末尾带 `ROWID='...'` | 不带 | ✅ 解析器本来就忽略 |
| CLOB（行内） | 有值 | **有值**（30000 字节以内实测内联） | ✅ |

四处补齐都是**纯增量**：Oracle 从不产生 `DATE'...'` / `TIMESTAMP'...'` / `0x...` / `OUT_CLOB` 这几种形态，加进去不会改变 Oracle 路径的任何行为。

（另有一处形态差异无害：`CHAR(10)` 存 `'ab'`，redo 渲染成 `'ab'` 而不是 Oracle 的 `'ab        '`——尾部空格不进逻辑日志。写回 CHAR 目标时驱动会补空格，语义不变。）

### 3.1 `OUT_CLOB` / `OUT_BLOB` 必须走"缺列"路径，不能算解析失败

实测一张带 `BLOB` + `CLOB` 的表，30 KB 的 CLOB 与 8 KB 的 BLOB 都越过了行内阈值：

```sql
UPDATE "SYSDBA"."LM_T5" SET "C" = OUT_CLOB, "B" = OUT_BLOB WHERE "ID" = 1;
```

`COLUMN_PRESENT` 对这两列返回 **1**（"存在"），`MINE_VALUE` 返回的也是字符串 `OUT_CLOB` / `OUT_BLOB`。也就是说这两列在语句里**有位置、没内容**。

正确归属是现有的 `ParsedChange.missingColumns` 路径：`sync.cdc.logminer.missing-lob-policy` 决定跳过还是停住，计数在状态面板可见。如果让它掉进通用的 `unsupported literal` 分支，它会变成"解析失败"，绕过 LOB 策略、进错计数器——是同一种数据缺失被记成了两类问题。

## 4. 已知有损项（必须在文档里写明）

| 项 | 实测 | 处理 |
|---|---|---|
| **小数位 > 9 截断** | `DECIMAL(38,10)` 存 `1.1234567890`，redo 与 `MINE_VALUE` 都给 `1.123456789`（截断，不四舍五入）。库里的值本身是完整的（`SELECT` 出来 10 位都在） | DM 逻辑日志的固有限制，程序侧无解。目标列 scale 超过 9 的表不能用日志 CDC，或接受末位丢失 |
| 行外 LOB | `OUT_CLOB` / `OUT_BLOB`，无内容 | 走 `missing-lob-policy` |
| `CHAR` 尾空格 | 不进 redo | 写 CHAR 目标由驱动补齐；写 VARCHAR 目标尾空格丢失 |
| 空串 | 存成 NULL（与 Oracle 同） | 语义一致，无需处理 |
| `SQL_UNDO` | 恒为 NULL | 不依赖它 |
| **CDC 延迟** | 只能挖归档，当前 redo 挖不了 | 延迟上界 = 归档切换间隔；DBA 必须挂定时切换（见 1.2） |

## 5. 结论

**GO。** 依据：

1. 带 `DICT_FROM_ONLINE_CATALOG` 的 `SQL_REDO` 是标准 SQL，INSERT 带全列、UPDATE 的 SET 只带改动列、WHERE 带全列旧镜像（`RLOG_APPEND_LOGIC=2` 下），与 Oracle 的形态同构。
2. 现有 `SqlRedoParser` + `OracleLiteralConverters` 只需四处**纯增量**改动（ANSI `DATE`/`TIMESTAMP`、`0x` 十六进制、`OUT_*`），Oracle 路径零影响——有现成的解析器矩阵测试兜底。
3. 位点语义（`CSCN`）、事务边界（`COMMITED_DATA_ONLY`）、提交标记行都能对上，位点状态机可以共用。

因此**不需要独立引擎**：抽一层 `MiningSource`（`LogMinerSession` 的建连/加归档/起停 + 表定位 + 字面量方言），Oracle 与 DM 是两个实现，上层 `LogMinerCdcConsumer` 的骨架（CSCN 事务批、租约、回放、位点、连续失败 park、LOB 策略）完全共用。`CaptureStrategy` 仍是 `LOGMINER_CDC`，按 ORACLE 家族放行 + 连接时能力探测。

**但要接受一个 DM 独有的代价**：CDC 延迟由归档切换周期决定（1.2），而 Oracle 可以挖当前联机 redo。这不是程序能修的，只能在部署前提里要求 DBA 挂定时切换作业，并在能力探测里把"最新归档太旧"报成提示。若某环境不允许定时切换，DM 就该退回轮询 + 分块比对，而不是硬上日志 CDC。

## 6. 适配层的最小清单

- 建连与起停：同一条 `Connection` 上 `ADD_LOGFILE`（区间内的全部**归档**）→ `START_LOGMNR(options => 2+16)` → 读游标 → `END_LOGMNR`；异常路径也要 `END_LOGMNR`。
- 归档发现：`V$ARCHIVED_LOG` 去掉 `DEST_ID = 1`；当前 redo 不参与挖掘，因此"没有新归档"是正常状态，按轮询等待而不是报错。
- 表定位：`SEG_NAME` 不可用，用 `SQL_REDO` 前缀 `"OWNER"."TABLE"`（或 `DATA_OBJ#` → `ALL_OBJECTS`）。
- 行事件：`OPERATION` 白名单 `INSERT`/`UPDATE`/`DELETE`，丢弃 `START`/`COMMIT`/`DDL` 及其它。
- 字面量：补齐 ANSI `DATE`/`TIMESTAMP`、`0x` 十六进制；`OUT_CLOB`/`OUT_BLOB` → 缺列。
- 位点：存 `CSCN`，与 Oracle 引擎同构。
- 前提探测：连上后检查 `ARCH_MODE`（`V$DATABASE` / `V$INSTANCE`）、`RLOG_APPEND_LOGIC`（`V$DM_INI`），任一不满足就在能力探测里给出可执行的修复语句，而不是等到挖不出数据再报错。
- 崖山（YashanDB）：接口同源但**未实测**，落地前必须按本记录的每一条复测，不能假定与 DM 相同——尤其是 `RLOG_APPEND_LOGIC` 语义、`SQL_REDO` 大小写、以及 `DATE`/`TIMESTAMP` 的渲染形态。

### 6.1 落地进度

| 项 | 状态 |
|---|---|
| 解析器四处增量（ANSI 时间、`0x`、`OUT_*`） | 已实现，矩阵测试覆盖，Oracle 路径零影响 |
| 方言缝 `MiningDialect` + `OracleLogMinerDialect` + `DmLogMinerDialect` + 能力探测选择 | 已实现；Oracle 行为由原有 14 个 open 序列测试兜住，全量 2803 测试绿 |
| 归档发现 / 上界 / 起停 / 游标（含 `>=` 续跑、空归档按空闲处理） | 已实现 |
| `ARCH_MODE`、`RLOG_APPEND_LOGIC`、归档保留与归档周期的能力探测与修复提示 | **未实现**（下一步） |
| `LOGMINER_CDC` 的 ORACLE 家族门禁 | **未实现**（下一步） |
| 崖山复测 | **未做** |
