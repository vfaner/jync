# Oracle → 达梦 迁移验收手册(手动 harness)

本文档是 Phase A(分块断点续传全量 + 并行装载 + ORA_ROWSCN 轮询)的**达梦侧验收步骤**。

自动化集成测试 `OracleMigrationHarnessIT`(真实 Oracle 23ai Free 容器 → H2)已覆盖:
NUMBER 主键并行分段全量、断点续传、ROWDEPENDENCIES 表的 ORA_ROWSCN 增量(含 UPDATE 可见性)。
但达梦官方不发布容器镜像,Oracle → 达梦 这一腿无法进 CI,按本手册在真实达梦环境手动验收。

运行方式(自动化部分,需本机 Docker):

```bash
mvn test -Dtest=OracleMigrationHarnessIT
```

> - 类名以 `IT` 结尾,默认 `mvn test` 不会执行;无 Docker 时自动跳过而非报错。
> - 镜像用 `gvenzl/oracle-free:23`(arm64 原生)。XE 21 没有 arm64 版,Apple Silicon 上
>   amd64 模拟运行会以 ORA-00443(PMON 起不来)失败;23 Free 对本文验收点行为一致。
> - Docker Engine ≥ 29 移除了旧版 API(<1.40),而 testcontainers 1.21.3 内嵌的
>   docker-java 默认协商 1.32,会被判定「无可用 Docker」而整体跳过。此时加参数:
>   `mvn test -Dtest=OracleMigrationHarnessIT -Dapi.version=1.44`。

---

## 1. 环境准备

| 角色 | 要求 |
|---|---|
| 源库 | Oracle 11g+;本机验证可用容器:`docker run -d -p 1521:1521 -e ORACLE_PASSWORD=oracle gvenzl/oracle-free:23`(Apple Silicon 原生;Intel 机器也可用 `gvenzl/oracle-xe:21`) |
| 目标库 | 达梦 DM8,已建好业务用户(下文以 `JYNC_TEST` 为例),驱动已内置无需另装 |
| Jync | `jync.jar`(v1.4.0+),`java -jar jync.jar` 启动,浏览器访问 8081 端口 |

### 1.1 Oracle 源库造数

用具备建表权限的用户(如 `SYSTEM` / `HR`)执行:

```sql
-- 普通表:验证分块 + 并行全量(NUMBER(18) 主键,零刻度才会走并行分段)
CREATE TABLE ITEMS (
  ID    NUMBER(18) PRIMARY KEY,
  NAME  VARCHAR2(50),
  PAYLOAD VARCHAR2(200)
);

BEGIN
  FOR i IN 1..100000 LOOP
    INSERT INTO ITEMS VALUES (i, 'row-' || i, LPAD('x', 200, 'x'));
  END LOOP;
  COMMIT;
END;
/

-- ROWDEPENDENCIES 表:验证 ORA_ROWSCN 轮询割接
CREATE TABLE SCN_T (
  ID   NUMBER(18) PRIMARY KEY,
  NAME VARCHAR2(50)
) ROWDEPENDENCIES;

INSERT INTO SCN_T VALUES (1, 'one');
INSERT INTO SCN_T VALUES (2, 'two');
INSERT INTO SCN_T VALUES (3, 'three');
COMMIT;

-- 无主键表:验证 UI 上「不支持断点续传」黄色标记
CREATE TABLE NO_PK (
  A VARCHAR2(20),
  B VARCHAR2(20)
);
INSERT INTO NO_PK VALUES ('a1', 'b1');
INSERT INTO NO_PK VALUES ('a2', 'b2');
COMMIT;
```

> 验收压力大时把 `100000` 放大到百万级;断点续传验收建议 ≥ 100 万行,否则全量太快、来不及中断。

### 1.2 达梦目标库

无需预建表:在项目配置里勾选「同步表结构」由 Jync 自动建表;
如目标端 DDL 由 DBA 管控,可手工建对应表(`NUMBER(18)` → `NUMBER(18)` 或 `BIGINT`,`VARCHAR2` → `VARCHAR`)。

---

## 2. 项目配置

1. 数据库配置页新建两个连接:
   - 源:`ORACLE`,`jdbc:oracle:thin:@//<host>:1521/<service>`,填用户名密码,测试连通。
   - 目标:`DM`(达梦),`jdbc:dm://<host>:5236`,填 `JYNC_TEST` 用户,测试连通。
2. 新建项目,源选 Oracle、目标选达梦,勾选 ITEMS / SCN_T / NO_PK 三张表。
3. 全量参数(设置页或项目高级配置):
   - `sync.chunk-size`:5000(默认 2000,验收并行时可调大)
   - `sync.full-load-parallelism`:4(>1 才会分段并行;上限 8)
4. 增量策略:
   - ITEMS:留默认(单整数主键 → IDENTITY 游标)。
   - SCN_T:确认项目详情里增量策略显示「行级 SCN / Row SCN」——这是 ROWDEPENDENCIES 探测生效的标志。若显示为主键 ID,说明探测没通过(检查连接用户能否查 `ALL_TABLES`)。
   - NO_PK:预期策略为 NONE 或 FULL_COMPARE(行数少),且全量运行中/运行后 UI 出现「不支持断点续传」黄色徽标。

---

## 3. 验收项

### 3.1 分块全量 + 并行(对应 A2/A3)

启动全量后观察项目详情页:

- [ ] 进度区显示「并行全量: x/y 段完成、n 块已提交」(parallelism>1 且有整数/零刻度 NUMBER 主键)。
- [ ] ITEMS 全量完成后,达梦端行数一致:

```sql
-- 达梦端
SELECT COUNT(*) FROM JYNC_TEST.ITEMS;   -- 期望 100000
SELECT SUM(ID) FROM JYNC_TEST.ITEMS;    -- 期望 100000*100001/2 = 5000050000
```

### 3.2 kill / 断点续传(对应 A2 验收)

1. 重新触发 ITEMS 全量(可先把达梦端表清空),在进度条走到 30%–70% 时直接 `kill -9` Jync 进程。
2. 重启 `java -jar jync.jar`,再次对该项目执行同步。
3. 验收:
   - [ ] 日志出现「resuming from checkpoint」类字样,已完成的分段(并行)或已提交的块直接跳过;
   - [ ] 达梦端最终行数与 SUM(ID) 校验值仍完全一致(upsert 幂等,重叠部分不产生重复行);
   - [ ] 全量成功后 `chunk_checkpoint` 表中该项目该表的检查点被清空(Jync 元数据库 H2,设置页可查看或停服后查 `~/.jync/jync.mv.db`)。

### 3.3 ORA_ROWSCN 轮询割接(对应 A4/M4)

1. 全量完成后开启周期同步(如 30 秒)。
2. 在 Oracle 端做一轮变更并**各自单独提交**:

```sql
INSERT INTO SCN_T VALUES (4, 'four');
COMMIT;
UPDATE SCN_T SET NAME = 'one-updated' WHERE ID = 1;
COMMIT;
```

3. 验收(一个轮询周期后在达梦端查):

```sql
SELECT COUNT(*) FROM JYNC_TEST.SCN_T;              -- 期望 4
SELECT NAME FROM JYNC_TEST.SCN_T WHERE ID = 4;     -- 期望 'four'
SELECT NAME FROM JYNC_TEST.SCN_T WHERE ID = 1;     -- 期望 'one-updated'
```

   - [ ] **UPDATE 可见**是核心验收点(IDENTITY 主键游标永远看不到存量行更新,ROW_SCN 可以);
   - [ ] 项目详情页游标值随每轮 COMMIT 递增(SCN 是数字);
   - [ ] 删除同步不了:这是轮询方案的已知边界,UI 策略帮助文案已注明,不算缺陷;
   - [ ] 割接演练:停 Oracle 端写入 → 等最后一个周期跑完(游标不再变化、增量为 0 行)→ 应用切到达梦。

### 3.4 无主键表 UI 提示(对应 A1/A5)

- [ ] NO_PK 全量运行中或完成后,项目详情页该表出现「不支持断点续传」黄色徽标,悬浮提示说明加主键才能分块续传。

### 3.5 结构同步抽查(达梦方言)

- [ ] ITEMS 自动建表时达梦端主键、列类型正常(`NUMBER(18)`/`VARCHAR2(50)` 或映射后的等价类型);
- [ ] 增量 upsert 在达梦端走 MERGE,重复同步不产生重复行。

---

## 4. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| SCN_T 策略不是「行级 SCN」 | 表不是 `ROWDEPENDENCIES` 建的(查 `SELECT DEPENDENCIES FROM ALL_TABLES WHERE TABLE_NAME='SCN_T'`,应为 `ENABLED`);或源库产品名不是 Oracle(达梦/崖山不探测,属白名单设计) |
| ITEMS 没走并行 | 主键非单列整数;`NUMBER` 主键必须是零刻度且精度 ≤18;`full-load-parallelism` 需 >1;MIN/MAX 跨度过大(超 long 安全范围)会自动回退顺序 |
| 达梦连接失败 | 驱动已内置(DmJdbcDriver18),检查 URL `jdbc:dm://host:5236` 与防火墙;大小写敏感参数按达梦实例初始化配置来 |
| kill 后重启全量从头开始 | 确认 kill 前项目确实在「全量」阶段(增量周期不产生检查点);确认 `sync.chunk-size` > 0 |
