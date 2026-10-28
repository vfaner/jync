# 达梦 DBMS_LOGMNR 实测记录

这份记录是一次**可行性验证**，不是功能文档：用于判断 DM / 崖山要不要走独立的日志 CDC 引擎，以及如果走，适配层要补什么。所有结论都来自一次真实 DM8 实例（Docker `xuxuclassmate/dameng:latest`，`DB Version: 0x7000c`）。

## 1. 环境前提（全部在 DBA 侧，与程序无关）

| 前提 | 做法 | 说明 |
|---|---|---|
| 归档模式 | `dmarch.ini` 里配 `ARCH_TYPE=LOCAL` + `ARCH_DEST`，`dm.ini` 里 `ARCH_INI=1`，重启实例，再 `ALTER SYSTEM SWITCH LOGFILE` | 只改 `ARCH_INI` 不建 `dmarch.ini` 时，`V$ARCHIVED_LOG` 一直是空的 |
| 逻辑日志 | `RLOG_APPEND_LOGIC=1`（重启生效） | 相当于把"行镜像"写进 redo，OpenLog 类工具都靠它 |
| 挖掘账号 | 需要 `DBMS_LOGMNR` 执行权限 + 读 redo/归档的权限 | 和 Oracle 一样，是**授予账号**，不是改程序 |
| 保留期 | 归档文件要覆盖最长停机窗口 | 归档被清理 = 位点失效 = 只能重建基线 |

代价与 Oracle 一致：redo 体积变大（行镜像进 redo），归档要留够。

## 2. 接口：与 Oracle 同名，语义不完全等价

`SYS.DBMS_LOGMNR` 包存在，公开过程只有这些：

`ADD_LOGFILE` / `START_LOGMNR` / `END_LOGMNR` / `REMOVE_LOGFILE` / `COLUMN_PRESENT` / `MINE_VALUE`

`V$LOGMNR_CONTENTS` 有 61 个列，列名与 Oracle 完全一致（`SCN`、`CSCN`、`TIMESTAMP`、`OPERATION`、`SEG_OWNER`、`SEG_NAME`、`TABLE_NAME`、`SQL_REDO`、`SQL_UNDO`、`ROW_ID`、`ROLLBACK`…）。

**但同样的语句在 DM 上给出的是不同的内容**：

| 列 | Oracle 行为 | DM8 实测 | 影响 |
|---|---|---|---|
| `SEG_NAME` / `SEG_TYPE` | 表名 / 对象类型 | **全为 NULL** | 无法按表名过滤，只能自己解析 |
| `SEG_OWNER` | schema 名 | **数字 id**（如 `150994945`） | 需要反查字典 |
| `DATA_OBJ#` | 对象号 | `1058`，可经 `ALL_OBJECTS.OBJECT_ID` 反查到 `SYSDBA.LM_T1` | 只能走对象号这唯一可靠路径 |
| `SQL_REDO` | `insert into "S"."T"(...) values(...)` | `"150994945"."OBJ# 1058"("COL 1","COL 2","COL 3")` | **不能当 SQL 用**，也不能靠它解析列名：列名被改写成 `COL n` |
| `ROW_ID` | 行标识，UPDATE 保持稳定 | 对 UPDATE / DELETE 为 NULL，且是递增序列 | 不能用 `ROW_ID` 匹配前后镜像 |
| `CSCN` | 提交 SCN | 同一事务所有行相同（实测均为 40315），但 `SCN` 与 `CSCN` 语义需自行验证 | 取位点只能用 `CSCN` 一类，不能拿 `SCN` |
| 额外行 | 无 | 会出现 `OPERATION=ROLLBACK` 之类的事务状态行 | 必须按 `OPERATION` 白名单过滤 |
| `DBMS_LOGMNR.MINE_VALUE` | 按列名取值 | `mine_value(redo_value,'COL 2')` 报 `[-6803] Invalid parameter value` | **取列值要走 `SQL_REDO`/`COLUMN_PRESENT` 的另一条路，Oracle 的写法不能照搬** |

## 3. 结论

1. **`DBMS_LOGMNR` 在 DM 上是"同名不同实"**：过程签名对得上，`V$LOGMNR_CONTENTS` 的列名对得上，但载荷（表名、列名、行标识、SQL 文本）全都要另想办法。
2. 因此"沿用 Oracle 引擎、只改连接串"走不通：Oracle 引擎的解析层直接吃 `SQL_REDO` 里的标准 SQL 并按 `SEG_OWNER.SEG_NAME` 定位表，这两点在 DM 上都不成立。
3. 换个角度，"照抄 Oracle 全流程"也不划算：真正能复用的是**骨架**（位点记录、基线、租约、回放、幂等 upsert），不能复用的是**解析与定位**这一段。独立引擎 = 骨架复用 + 适配层新写，而不是从零。

## 4. 若要做，适配层的最小清单

- 表定位：`DATA_OBJ#` → `ALL_OBJECTS.OBJECT_ID` 反查 `OWNER`/`OBJECT_NAME`，按表名缓存。
- 行事件解析：以 `OPERATION`（`INSERT`/`DELETE`/`UPDATE`）+ `COLUMN_PRESENT`/`MINE_VALUE` 为主路径，`SQL_REDO` 仅用于日志与排错，**不参与解值**。
- 列名还原：靠 `ALL_TAB_COLUMNS.COLUMN_ID` 与 `COL n` 的对应关系重建，不能信 `SQL_REDO` 里的名字。
- 事务过滤：忽略 `ROLLBACK` 等状态行；提交边界以 `CSCN` 变化为准。
- 位点：存 `(CSCN, 归档文件序号)`，与 Oracle 引擎的 SCN 位点结构接近，但不能共用同一份状态机实现。
- 崖山（YashanDB）：接口同源但未实测，落地前必须按本记录的项目逐条复测，不能假定与 DM 相同。
