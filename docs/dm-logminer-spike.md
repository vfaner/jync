# 达梦 DBMS_LOGMNR 实测记录

这份记录是一次**可行性验证**，不是功能文档：用于判断 DM / 崖山要不要走独立的日志 CDC 引擎，以及如果走，适配层要补什么。第 1–6 节的结论来自一次真实 DM8 实例（Docker `xuxuclassmate/dameng:latest`，`DB Version: 0x7000c`）；第 7 节是 2026-10-29 对崖山 YashanDB 23.4.7.100 的独立复测。

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
- 崖山（YashanDB）：**LogMiner 这条路已复测，结论是不支持**（第 7 节）。它接受 Oracle 式的归档与补充日志 DDL，却没有 `SYS.DBMS_LOGMNR` 与 `V$LOGMNR_CONTENTS`，因此不存在"配置对了就能挖"的路径。崖山有自己的逻辑日志通道 YStream（第 8 节，实测可用），但那不是 LogMiner，方言缝与 `SqlRedoParser` 都用不上；在 YStream 接线之前，崖山源库仍用轮询或全表比对。

### 6.1 落地进度

| 项 | 状态 |
|---|---|
| 解析器四处增量（ANSI 时间、`0x`、`OUT_*`） | 已实现，矩阵测试覆盖，Oracle 路径零影响 |
| 方言缝 `MiningDialect` + `OracleLogMinerDialect` + `DmLogMinerDialect` + 能力探测选择 | 已实现；Oracle 行为由原有 14 个 open 序列测试兜住，全量 2803 测试绿 |
| 归档发现 / 上界 / 起停 / 游标（含 `>=` 续跑、空归档按空闲处理） | 已实现 |
| `ARCH_MODE`、`RLOG_APPEND_LOGIC`、归档范围与新鲜度的能力探测与修复提示 | 已实现（`LogMinerCdcProbe` 的 DM 分支：①源库 ②版本 ③`ARCH_MODE` ④`DM_LOGIC_REDO` ⑤`DM_ARCHIVE_PERIOD` ⑥挖掘权限；Oracle 专属的表级补充日志 / LONG / CDB 检查在 DM 上不出现） |
| `LOGMINER_CDC` 的 ORACLE 家族门禁（保存校验、连接测试探测、项目页探测、推荐项、策略卡片） | 已实现，按 `resolvedFamily()` 判；能不能真挖由连上后的能力探测决定 |
| 基线起点 SCN 改由方言提供（Oracle `CURRENT_SCN` / DM `MAX(NEXT_CHANGE#)`，无归档时给出可执行提示） | 已实现（`CdcBaselineService.readCurrentScn` 走 `MiningDialect.highWaterScn`） |
| 崖山复测 | **已做（2026-10-29）**：崖山没有 `DBMS_LOGMNR`，LogMiner 在该产品上不可用，探测改为在 ①源库 一项上直接给出结论——见第 7 节 |
| 崖山 YStream 调研 | **已做（2026-10-29）**：崖山自研的逻辑日志引擎，Docker 23.4.7.100 实测端到端出 DML/DDL，客户端 jar 在 Maven 中央仓；v1.9.0 起以 `YSTREAM_CDC` 策略接入——见第 8 节 |

---

## 7. 崖山（YashanDB）复测记录（2026-10-29）

复测不是"再确认一次 DM 的结论"，而是逐条问崖山同样的问题。结论是**不成立**：崖山 23.4.7.100 里没有 LogMiner 这个能力本身。

实例：Docker 镜像 `yasdb/yashandb:23.4.7.100`（原生 aarch64），`V$VERSION` = `YashanDB SQL Enterprise Edition Release 23.4.7.100 aarch64`，`V$DATABASE.LOG_MODE=ARCHIVELOG`。

| 探测项 | DM8 实测 | 崖山 23.4.7.100 实测 |
|---|---|---|
| `SYS.DBMS_LOGMNR` 包 | 有 | **不存在**（`all_objects` 里 14 个内置包，无此包） |
| `V$LOGMNR_CONTENTS` | 有 | **不存在**（`YAS-02012 table or view does not exist`） |
| `V$RLOGFILE`（DM 方言探针） | 有 | 不存在 → DM 方言探测失败，崖山**不会**被识别成 DM |
| `V$DATABASE.CURRENT_SCN`（Oracle 方言探针） | 字段不存在 | **有** → 崖山被识别成 Oracle 方言 |
| `ARCH_MODE`（DM 的归档判断列） | 有（`Y`/`N`） | `YAS-04243 invalid identifier` |
| `V$DATABASE.LOG_MODE` | 不存在 | `ARCHIVELOG` |
| `SUPPLEMENTAL_LOG_DATA_MIN` | — | 有，默认 `NO`；`ALTER DATABASE ADD SUPPLEMENTAL LOG DATA` **执行成功**并翻成 `YES` |
| `RLOG_APPEND_LOGIC` / `V$DM_INI` | 必需参数与参数视图 | 参数视图是 Oracle 式的 `V$PARAMETER`/`V$SYSTEM_PARAMETER`，**没有** `RLOG_APPEND_LOGIC` 或任何 `%LOGIC%` 参数 |
| `V$ARCHIVED_LOG` | 有 | 有，`NAME`/`SEQUENCE#`/`FIRST_CHANGE#`/`NEXT_CHANGE#`/`COMPLETION_TIME` 齐全 |
| `ORA_ROWSCN` | 不存在 | 不存在（`YAS-04243`），行级 SCN 游标同样不成立 |
| `DBA_TAB_PRIVS` 里的 `DBMS_LOGMNR` 授权 | — | 0 行（因为包不存在） |

两点值得记录：

1. **崖山会"假装"满足 Oracle 的前提条件。** `LOG_MODE` 报 `ARCHIVELOG`、补充日志 DDL 执行成功、`CURRENT_SCN` 可读——如果不看包里到底有没有挖掘过程，探测会一路绿灯，然后在第一次挖掘时炸。所以 `LogMinerCdcProbe` 的 Oracle 分支在 ①源库 失败时**只返回这一项**，不再往下问补充日志、LONG、CDB 这些它答不了也办不到的问题。
2. **"未实测"这个措辞不能再用了。** 之前拒绝崖山的文案是"实测前拒绝"，暗示配置对了就能放开。实测结果是产品根本没有这个能力，文案改成实测事实 + 明确给出替代路径（时间戳/数值键轮询、全表比对）。

崖山作为 Jync 的**源库**仍然可用，只是走轮询或全表比对；`OracleMetadataReader` 也已按 `isOracleProduct()` 把 `ORA_ROWSCN` 探测排除在外，与本次实测一致。

> 第 7 节说的是"没有 LogMiner"，不是"没有日志解析"。崖山有自己的引擎 **YStream**，见第 8 节。

## 8. 崖山 YStream 专项实测（2026-10-29）

结论：崖山有自研的逻辑日志解析引擎 **YStream**，23.4.7.100 单机 Docker 实测**端到端可用**——不需要 LogMiner，也不需要触发器。

环境同第 7 节（`yasdb/yashandb:23.4.7.100`，aarch64，端口 1688）。

| 步骤 | 动作 | 实测结果 |
|---|---|---|
| ① 管理包 | `DBMS_YSTREAM_ADM` | **存在且可用**。它是内置包：`DBA_OBJECTS` / `DBA_PROCEDURES` 里查不到同名记录（对照：`DBMS_OUTPUT` 在 `DBA_PROCEDURES` 里有 7 行，`DBMS_AUDIT_MGMT` 0 行）——"字典里没有"不等于"产品没有"，第一版复测就是在这里判错的 |
| ② 运行时视图 | `V$YSTREAM_SERVER` / `V$YSTREAM_STAT` / `DBA_YSTREAM_TABLES` / `DBA_YSTREAM_PARAMETERS` | 都在，背后是 7 张 `YSTREAM_*$` 表 |
| ③ 建服务 | `DBMS_YSTREAM_ADM.CREATE('probe')` | 成功，`STATUS=CREATED`，`START_SCN` 落在建服务那一刻 |
| ④ 加表 | `ADD_TABLES(server, 'SCHEMA.TABLE', schemas)`（三个字符串参数） | 成功；第二参是 `schema.table` 清单，第三参是**整库** schema 清单（传了就会捕获该 schema 下所有表，`DBA_YSTREAM_TABLES` 里表现为一行表名为空）。只加指定表时第三参传 `''` |
| ⑤ 起服务 | `DBMS_YSTREAM_ADM.START('probe')` | `STARTED`；客户端连上后变 `RUNNING` |
| ⑥ 客户端 | `com.yashandb:Ystream:1.9.27`（Maven 中央仓，Apache-2.0，214 KB） | 连 `jdbc:yasdb://host:1688`，`serverName` 填服务名 |
| ⑦ 取 LCR | `client.next()` | Metadata / XactBegin / DDL / DML / XactCommit 五类全出 |

实测输出（节选）：

```
YSTREAM_DML INSERT YS_SPIKE.T1 sql=< INSERT INTO "YS_SPIKE"."T1"("ID","V") VALUES (20,'p')>
YSTREAM_DML UPDATE YS_SPIKE.T1 sql=< UPDATE "YS_SPIKE"."T1" SET "V" = 'q' WHERE "ID" = 20 AND "V" = 'p'>
YSTREAM_DML DELETE YS_SPIKE.T1 sql=< DELETE FROM "YS_SPIKE"."T1" WHERE "ID" = 20 AND "V" = 'q'>
YSTREAM_DDL ddl=<CREATE TABLE YS_SPIKE.t2 (id INT PRIMARY KEY, v VARCHAR(50))>
Position{commitScn=882267680494608384, logPosition=LogPosition{instanceId=0, groupLsn=6456, groupOffset=220, batchRowId=0}}
```

要点：

1. **行事件带全列镜像**，三类 DML 齐全，`LcrType` 另分 `YSTREAM_XACT`（提交，带 `commitScn`）与 `YSTREAM_DDL`（DDL 原文）。`getStatement()` 直接给可读 SQL，但那是**源端**方言，回放仍要走 Jync 自己的方言层。
2. **DDL 原生支持**：`CREATE TABLE`、`ALTER TABLE` 的 `ddlText` 是原文，不需要 LogMiner 的 `ddl` 选项和二次查视图。
3. **位点是可序列化的五元组**：`Position{commitScn, instanceId, groupLsn, groupOffset, batchRowId}` 全是整数，`SystemChangeNumber` 还能给出提交时间戳。
4. **表元数据随流下发**（`YSTREAM_METADATA` + `TableMetadata`：列类型/可空/长度/主键），不必另查字典。
5. **前提条件**：`LOG_MODE=ARCHIVELOG`（镜像默认已是）；连接用户需 `CREATE SESSION` + `SELECT ANY TABLE` + `YSTREAM_CAPTURE` + **`SELECT ANY DICTIONARY`**（第二轮实测：没有字典权限时 `V$YSTREAM_SERVER` / `DBA_YSTREAM_TABLES` 直接报"视图不存在"，而客户端仍能连——只看客户端连没连是查不出来的；另：崖山**没有** `SESSION_PRIVS` / `SESSION_ROLES`，权限判断要用 `USER_SYS_PRIVS` + `ROLE_SYS_PRIVS` + `USER_ROLE_PRIVS`）。崖山也没有 `V$PARAMETER.SUPPLEMENTAL_LOG_DATA_MIN` 这个开关，补充日志是**逐表**的（见第 6 条）。
6. **表级补充日志是硬前提，对每一张要捕获的表都成立**：没有 `ALTER TABLE ... ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS` 的表，DML **悄无声息地不进流**——不报错、不告警，服务照常 RUNNING。这一条在第二轮补测里用新建表（`NEW_T`）复现：建表后插入行、`ADD_TABLES` 之后仍然收不到；执行 ALTER 后下一次插入立刻出现（`[probe3] YSTREAM_DML INSERT YS_T.NEW_T ...`）。**第一版"新表不加也能出 DML"的结论是误判**（当时那批 DML 其实来自已 ALTER 过的 `t1`），已推翻。是否已加可逐表查：`ALL_LOG_GROUPS.LOG_GROUP_TYPE = 'ALL'`（系统生成的组名形如 `SYS_C_36`）。这是一次可逆的表参数 DDL，不新增对象、不写触发器，但**要求 DBA 执行**——Jync 只检测、只生成脚本，不代为执行。**第三轮实测（2026-10-29）补一条**：数据库级开关 `ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS`（`V$DATABASE.SUPPLEMENTAL_LOG_DATA_ALL=YES`）**不能替代表级组**——同一张表只有库级开关、`ALL_LOG_GROUPS` 为空时，INSERT / UPDATE / DELETE 一条都进不了流（服务照常 RUNNING）；把表级组加回来，下一次插入立刻出现，且**无需重启服务**；反过来中途 `DROP` 掉表级组，之后的变化立刻停流。所以探测与启动门禁只认 `ALL_LOG_GROUPS` 是**对的**，不要"顺手"改成接受库级开关——那等于把静默丢数据放行。
7. **一对一**：同一服务第二个客户端被拒（`YAS-02731 YStream server status is invalid, current status: RUNNING`）；每实例最多 32 个服务；存算一体分布式集群**没有**此功能。
8. 客户端正常 `close()` 后服务停在 `RUNNING`，数十秒后才回 `STARTED`，期间连不上（`YAS-02731`）；`STOP` + `START` 可立即复位，且**不改变 `START_SCN` / `RESTART_POSITION`**（第二轮实测：`STOP` 再 `START` 前后两者完全一致），所以这是一条安全的"复位"路径。异常退出的客户端会在 `ERROR` 列留下 `YAS-02094 current session has been killed or canceled`，下一次 `START` 会清空。`DROP` 要求服务先 `STOP`，否则同样报 `YAS-02731`。
9. **`RECOVER` 必须显式给位点，且按"严格晚于该位点"续放**：`StartMode.RECOVER` 不带 `setRecoverPosition` 会在 `build()` 阶段直接抛 `Ystream-0101 recoverPosition cannot be null`；带一个真实提交位点则从它的下一条开始（第二轮实测：从第 6 行插入的位点起，回放正好从第 7 行开始）。**第一版"服务被复位后中间位点会静默空转"的结论不成立**——那次传的是命令行临时拼的、服务日志里并不存在的位点。仍建议留"长时间无进展"看门狗兜底。
10. **`setAppliedPosition` 不是持久化手段**：服务端 `RESTART_POSITION` 只随客户端的周期 checkpoint 前进，而且 checkpoint 里带的是**服务端自己**的读取位置；客户端在 `close()` 前才调一次 `setAppliedPosition`，下一轮 checkpoint 根本不会发生，`RESTART_POSITION` 原地不动（第二轮实测，两次）。**续放位点必须由 Jync 自己落库**，不能指望服务端记住。
11. **捕获起点由 `START` 钉住，不是 `CREATE`**：`CREATE` 后 `START_SCN=882300491771490304`、`START_POINT=0-3-800-3365`（占位）；`START` 之后两者一起推进到起服务那一刻（实测 `START_SCN=882300565265162240`）。所以**基线必须在 `START` 之后跑**，否则基线期间的变化落在 `START_SCN` 之前，永远不会被回放。
10. **jar 版本要配对**：`Ystream` 1.9.27 依赖 `yashandb-jdbc` 的 `SOFTWARE_VERSION` 字段，而 Jync 当前钉的 1.7.10 会 `NoSuchFieldError`；实测 `yashandb-jdbc` 1.9.27 / 1.10.7 都能连 23.4.7.100。

### 8.1 运行时对象、状态与调用约定（第二轮补测，2026-10-29）

| 对象 | 内容 |
|---|---|
| `V$YSTREAM_SERVER` | `SERVER_NAME`、`STATUS`、`START_SCN`、`START_POINT`、`RESTART_POSITION`、`APPLIED_POSITION`、`CAPTURE_POSITION`、`ERROR`、`CREATE_TIME`、`CONNECT_USER` |
| `V$YSTREAM_STAT` | 每服务计数器：`CAPTURE_SCN`、`SEND_COUNT`/`SEND_SIZE`、`SPILL_SIZE`、`RECORD_FETCH_COUNT`、各阶段耗时、`XACT_MEMORY_USED` 等 |
| `DBA_YSTREAM_TABLES` | 服务 → 表成员（`SERVER_ID`、`SCHEMA`、`TABLE_NAME`），可用来判断"这张表是否已在捕获范围里" |
| `DBA_YSTREAM_PARAMETERS` | 服务参数（`PARAM_NAME`/`PARAM_VALUE`/`PARAM_DEFAULT`） |

- `STATUS` 取值实测三种：`CREATED`（建好未起）、`STARTED`（服务在跑、无客户端）、`RUNNING`（有客户端连着）。
- 生命周期：`CREATE` → `ADD_TABLES` → `START` → 客户端 attach；释放：先 `STOP` 再 `DROP`。`DROP` 在 `STARTED` 状态下直接报 `YAS-02731`。
- `ADD_TABLES` 的第二个参数是**一个字符串里的表清单**，大表集要分批调用（单表约 30~40 字符，建议每 50 张一批）。
- 位点的文本形就是五元组：`<scn>-<instanceId>-<groupLsn>-<groupOffset>-<batchRowId>`，例如 `882301780458180608-0-6744-220-0`。
- 客户端配置项：`host` / `port` / `serverName`(服务名) / `user` / `password` / `database` / `deserializer` / `pollTimeout` / `startMode` / `recoverPosition`；`startMode=START` 只取"从现在起"的新事件，历史补放必须 `RECOVER`。
- LCR 形态：每事务 `YSTREAM_XACT(BEGIN)` → 若干 `YSTREAM_DML` → `YSTREAM_XACT(COMMIT)`，提交 LCR 带 `commitScn` 与完整位点；`YSTREAM_METADATA` / `BEGIN` 的 `getPosition()` 会抛 `YstreamUnsupportedOperationException`，取位点必须 try/catch。
- 列值：`getNewValues()` / `getOldValues()` 返回 `YstreamColumns`，逐列 `YstreamColumn.getColumn().getColumnName()` + `getData()` + `isNull()`；`getColumnsWithoutDeleted()` 剔除本行未涉及的列（UPDATE 的老值里只含被改列与键）。`hasChunkData()` 为 true 时该行的大字段走后续 `YSTREAM_CHUNK` LCR。
- 每实例 ≤32 个服务；服务只在主库可建；客户端 1:1（同一服务第二个客户端 `YAS-02731`）。

### 8.2 对 Jync 的意义

- 这是崖山**唯一不侵入原库的实时通道**：不建触发器、不建对象；代价是**每张要捕获的表**都要一次可逆的补充日志 DDL（`ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS`，由 DBA 执行，Jync 只检测并生成脚本）。
- 它**不是 LogMiner**：`MiningDialect`、`SqlRedoParser` 这层方言缝用不上。需要新策略 `YSTREAM_CDC` + 新消费者（客户端库接入、LCR 分派、位点 = `Position` 五元组、Txn 边界 = `YSTREAM_XACT`），租约、暂停/恢复、连续失败 park、基线锚点等骨架可以照用现有 CDC 骨架。
- 产品约束要写进页面与文档：≤32 个任务/源实例、客户端 1:1、源端必须归档 + 补充日志、服务只能在主库建、客户端必须连"跑着该服务的那个节点"。
- 依赖成本：`Ystream` 214 KB + `yashandb-jdbc` 502 KB，Apache-2.0，都在 Maven 中央仓。

判定：**可行**，工作量与 DM LogMiner 适配相当（一个消费者 + 一个策略值 + 家族门禁 + 能力探测），收益是把崖山从"只能轮询（看不到 DELETE）"提升到真正的实时 CDC。
