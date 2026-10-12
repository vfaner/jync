# Jync v1.4.0

本版主题是**初始全量装载的可靠性与吞吐**:从「一条流式查询整表拉完」升级为 **keyset 分块 + 每块提交 + 检查点断点续传**,崩溃或 kill 进程后从上一块继续,不再整表重来;单列整数主键的表还可开启**表内并行**(MIN/MAX 切不重叠区间,至多 8 个 worker)。Oracle 源新增行级 **ORA_ROWSCN** 增量游标(建表带 `ROWDEPENDENCIES` 的表,已同步行被 UPDATE 也能看见)。全部新行为都有真实 Oracle 容器集成测试背书。

## 本次更新

### 全量装载:分块与断点续传(默认开启)

- 有主键的表按 `WHERE pk > ? ORDER BY pk` keyset 翻页(不用 OFFSET,深分页不衰减),每 `sync.chunk-size` 行(默认 10 万)提交一次,并把翻页边界写入 `chunk_checkpoint` 检查点表
- 崩溃 / kill 进程后,重启跳过已提交的块,从检查点边界继续装载;边界重叠处的少量重复投递由幂等 upsert 收敛;全量成功后检查点自动清空
- `sync.chunk-size: 0` 可完全回到旧版单条流式读取
- 无主键表无法分块(没有可翻页、可幂等的键),仍是流式整表拉取,中断即整表重来 —— 项目详情页为此类表标注「不支持断点续传」黄色徽标,悬浮说明加主键即可启用

### 全量装载:表内并行(默认关闭,升级零惊喜)

- 单列整数主键表(含 Oracle `NUMBER(p≤18,0)` 零刻度主键)先 `SELECT MIN(pk), MAX(pk)` 定界,切成互不重叠的区间,由 `sync.full-load-parallelism`(默认 1 = 旧行为,上限 8)个 worker 各领一段独立翻页
- worker 按块借还连接;并行度超过 `sync.max-pool-size`(默认 5)时在连接池上排队
- 主键跨度超出 long 安全范围或无法定界时自动回退顺序装载;复合主键、无主键表始终顺序 —— 并行只对能安全切分的表生效
- 运行中项目详情页实时显示「x/y 段完成、n 块已提交」

### Oracle 行级 ORA_ROWSCN 增量游标

- 建表带 `WITH ROWDEPENDENCIES` 的表,在没有任何可用的最后修改时间列时,用行级提交 SCN 做游标:**已同步行被 UPDATE 也能看见**(主键 / 创建时间游标永远做不到这一点)
- 优先级低于真实的最后修改时间列、高于创建时间列与数字主键;块级 SCN(建表未带 `ROWDEPENDENCIES`)粒度太粗,不会被选用
- 按数据库产品名白名单,只在真 Oracle 上启用探测;达梦 / 崖山虽复用 Oracle 字典读取器,但不探测
- 已知边界:检测不到删除;每轮取 `MAX(ORA_ROWSCN)` 是一次全表扫描(UI 策略帮助文案均已注明)

### 真实环境修复(由 Oracle 集成测试首跑抓出)

- **Oracle 分页泄漏辅助列**:offset=0 的 ROWNUM 包装把 `RNUM_` 投影进结果集,而数据复制按结果集元数据构建 INSERT 列,导致 Oracle 源的分块全量每块必失败;现 offset=0 走纯 `ROWNUM <=` 封顶,不再产生辅助列
- **并行门控不认识 Oracle 主键**:原门控只认 INTEGER/BIGINT 等 JDBC 类型,而真实 Oracle `NUMBER(18)` 主键上报为 `NUMERIC(18,0)`,Oracle 源永远进不了并行分段;现放行零刻度且精度 ≤18 的 NUMERIC/DECIMAL,带小数或精度 19(可能超出 long)仍拒绝,避免破坏分段不重叠

### 测试

- 单元测试增至 **675** 个(默认套件,Docker 无关)
- 新增 `OracleMigrationHarnessIT`:testcontainers + Oracle 23ai Free 真实容器 → H2,验证 ①`NUMBER(18)` 主键 5000 行 4 段并行全量(含真实字典探测断言)②中断后从检查点续传只重读后半段 ③`ROWDEPENDENCIES` 表完整 ORA_ROWSCN 链路(探测 → 策略解析 → 增量周期投递 INSERT 与已同步行 UPDATE)。类名 `*IT` 不进默认套件,无 Docker 自动跳过;运行方式见 README「测试」一节
- 新增 `docs/migration-harness-oracle-to-dm.md`:Oracle → 达梦手动验收手册(造数 SQL、项目配置、kill/续传、ORA_ROWSCN 割接步骤、UI 徽标核对、FAQ)。达梦无官方容器镜像,该腿不进 CI

## 升级

换 jar、重启即可:元数据库(`ddl-auto: update`)自动新增 `chunk_checkpoint` 表与 `sync_progress.resumable_load` 列,已存加密口令不受影响。

**一处默认行为变化**:有主键表的初始全量默认改为分块装载(每块独立提交 + 写检查点)。这不改变数据结果(写入仍幂等),但提交粒度从「整表一次」变为「每块一次」;需要完全恢复旧版行为时设 `sync.chunk-size: 0`。

## 完整文档

[README(中文)](https://github.com/vfaner/jync/blob/main/README.md) · [README (English)](https://github.com/vfaner/jync/blob/main/README_EN.md)
