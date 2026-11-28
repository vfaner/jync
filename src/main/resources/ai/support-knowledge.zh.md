# Jync 使用知识

## 作者与联系方式

Jync（捷同）由沐沐开源工坊开发和维护，采用 Apache-2.0 许可。官网：https://jync.qqmu.com。
联系方式：QQ 817094 / 2912167928；微信 qqmu66；QQ 群 426669837；邮箱 vfaner@88.com。
「Jync / 捷同」名称、logo 与作者署名不得替换后发行，详见仓库中的 TRADEMARK.md。

## 文档与手册下载

- 官网与在线文档：https://jync.qqmu.com
- 用户操作手册：https://jync.qqmu.com/docs/jync-user-guide.pdf
- 部署与运维手册：https://jync.qqmu.com/docs/jync-ops-guide.pdf
- 产品介绍：https://jync.qqmu.com/docs/jync-overview.pdf
- 部署说明：https://jync.qqmu.com/deploy.html
- 数据库能力地图：https://jync.qqmu.com/database.html
仓库内 docs/configuration.md 是配置、调优与 CDC 前提的权威清单。

## 新建同步任务

在「同步任务 → 实时同步」点「新建任务」，先选源连接和目标连接，保存后在详情页选择表、
设置同步策略并启动。源连接必须是「源」用途，目标连接必须是「目标」用途；目标为 Redis 时
选「Redis 缓存目标」。

## 首页看板

「首页」每 10 秒自动刷新：顶部四张卡是任务数/运行中、ETL 任务/启用数、连接数与同步表数、
24 小时变更数；右侧仪表是主机负载、CPU、内存与磁盘；下方是 24 小时趋势图（同步/ETL 可切换）
与最近活动。项目概览列出每张任务的表数、已同步行数与上次同步时间，点任务名进入详情。
看整体是否健康先看这里，单任务的速度与失败细节看任务详情「性能」与「变更日志」。

- 看板顶部「实时状态 · 更新于 HH:mm:ss」的徽标会随每次 10 秒刷新写上最新更新时间；同步任务卡里「异常任务」以红色数字并列显示。
- 四块资源环（负载 / CPU / 内存 / 磁盘）悬停或键盘聚焦可看已用、可用与使用率；CPU 卡还会列出处理器型号、物理/逻辑核心与系统，点「CPU 占用率 top5」可展开只读进程榜（展开期间暂停刷新）。资源环每 5 秒刷新一次。

## 任务创建进阶

- 表名映射：在任务详情的表选择区可把源表写到不同的目标表名；同一次同步内目标名不能重复，
  也不能与其他源表冲突，运行中不能修改表选择或映射。
- 数据预览：只读抽样查看源或目标的若干行，用于确认表选对、清洗规则生效；不会写目标。
- 克隆：从已有任务复制同步设置，目标库刻意留空，需要另选目标后保存。
- 批量创建：读取一次源库表清单，按估算大小（目录统计，可选精确统计）均衡拆成 1–50 个任务，
  创建后保持停止状态，需逐个确认再启动；每个任务独占自己的连接池，同时启动会成倍加压。

## 实时同步的三种策略

- 全量加载：首轮把每张表的基线搬完，按 chunk 分块、可多 worker 并行，断点记在分块表里。
- 增量轮询（POLLING）：按游标列周期查询「游标 > 上次值 且 ≤ 本次最大值」，只做 upsert，
  因此看不到物理删除；要求游标列单调、时区与精度稳定。
- 日志 CDC：长驻线程连续回放 binlog / LogMiner / WAL / 变更表 / YStream，能捕获
  INSERT、UPDATE、DELETE，延迟最低，但需要日志保留、权限与补充日志。
选择顺序：能开日志就用 CDC；否则有可靠更新时间列用轮询；历史小表可用全表比对
（FULL_COMPARE，默认上限 sync.full-compare-max-rows=20000 行）；都不满足时只做一次全量（NONE）。

## 游标策略详解

自动解析顺序（项目里显式指定游标列时优先用指定值）：
1. TIMESTAMP：列名命中 UPDATE_TIME / UPDATED_AT / UPDT_TM / UPDATE_TM 等且为时间类型，捕获新增与更新，最可靠；复合主键或非数字主键不影响时间列被选中。常见的建表时间缩写 CREATE_TM / CRT_TM 在第三步作为 IDENTITY 使用。
2. ROW_SCN：Oracle 表以 WITH ROWDEPENDENCIES 创建时的 ORA_ROWSCN 伪列，没有字段也能捕获更新，
   但每轮 MAX(ORA_ROWSCN) 是全表扫描。
3. IDENTITY：建表时间列或单列整型主键，只捕获新增，已有行的更新不可见。
4. FULL_COMPARE：每轮重读整表按主键 upsert，正确但昂贵，超过行数上限则拒绝。
5. NONE：找不到安全游标，只做一次全量，并在页面说明原因。
游标本身看不到删除：删除检测是单独的比较步骤，表不超过 sync.full-compare-max-rows
（默认 2 万行）时每轮比较两侧主键并删掉目标多出的行，超过则跳过并写明原因；调大
sync.delete-compare-max-rows 可分页比较（默认 0 关闭，代价是每轮全扫目标、逐键回源）。

## 写入期间暂停目标外键约束

- 「同步选项 → 写入期间暂停目标外键约束」默认开启。MySQL 目标是会话级 `SET FOREIGN_KEY_CHECKS=0/1`；SQL Server 目标是逐表 `ALTER TABLE ... NOCHECK` / `WITH CHECK CHECK CONSTRAINT ALL`，属于持久表状态。
- SQL Server 在关闭前先写标记；数据阶段无论成功失败都在 finally 恢复，恢复失败会作废物理连接，避免带 NOCHECK 回到连接池。进程被杀死时标记留下，启动时及之后每 5 分钟自动恢复（目标连接仍存在且没有其他节点持租约时）；目标连接已删除则只清标记。
- 助手不能修改这个开关。

## 无主键表（keyless）的限制

- 没有主键就没有 upsert：首轮全量按普通 INSERT 写入。目标表非空或无法确认目标为空时，这次全量会被拒绝，避免把整表再插一遍；清空目标表或开启「全量前清空目标表」后重试。
- 无主键全量改为**单条流式 SELECT（语句级快照）**，不再做 OFFSET 分页；不会因并发写入移动页码，但**不能断点续传**，中断后整表重来。目标必须确认空表。强烈建议加主键，或用 CDC / 只读窗口迁移。
- 日志 CDC 对无主键表只能基线播种，后续变更无法定位目标行；一源多目标的基线会跳过无主键表；FULL_COMPARE 对无主键表只做一次基线播种。

## 同步进度卡片怎么刷新

- 任务详情「各表同步进度」自动刷新：有全量在跑时约 2 秒一次，任务运行中约 5 秒一次
  （表数超过 200 时为 10 秒）。策略 / 游标列、全量状态、增量状态、上次同步与全量进度
  都会就地更新，不需要刷新页面；页面在后台时暂停，回到前台先补一次。
- 「全量进度」列只在还有未完成的全量时显示：正在装载，或停在中途仍可续传（有检查点）。
  所有行都显示 100% 时整列自动隐藏；Jync 全量完成的表显示绿色 100%，进行中的表显示
  `~百分比`；外部基线行没有 Jync 全量进度，显示「—」且会让该列保留；刚启动、还没写第一个
  检查点时显示「装载中…」。累计同步：全量按实际复制行数计（即使
  目标值没变），后续增量按实际变更计；全量进行中的「上次同步」取最新已提交检查点时间。
- 「策略 / 游标列」显示解析后的策略与游标列；显示「—」表示该表还没有进度记录，或没有
  可用游标（增量状态为 LIMITED）。全量完成后解析出的游标会在下一次自动刷新时出现。
- 游标列的黄色索引提示来自页面加载时对源库的索引探测；新设置或更换游标列后要刷新页面
  才会重新判断，这与同步状态是否自动刷新是两件事。

## 目标库表名 / 字段名大小写

- Jync 创建目标对象时按源端拼写加引号（MySQL 反引号、PostgreSQL / Oracle 双引号、
  SQL Server 方括号），本身不做大写转小写或小写转大写。
- 目标对象最终是什么大小写，由目标库的标识符折叠规则决定：MySQL 的
  lower_case_table_names（初始化时固定，1 / 2 会把表名存成小写）；openGauss / Vastbase
  一类产品的 lower_case_column_names（开启时连引号里的列名也会变小写）及表名相关设置。
  这是数据库自己的行为，Jync 无法按会话覆盖。
- Oracle 系目标（Oracle / 达梦 / 崖山）默认把裸标识符折叠成大写。若业务 SQL 依赖与
  源端（例如 MySQL 的小写 user_info）一致的大小写，可在任务详情「同步选项」打开「目标
  表名/字段名保持源端大小写」：之后按源端拼写加双引号（`"user_info"`），表名映射里的名字也保留写入时的
  拼写。该开关只影响 Oracle 系目标，默认关闭；非 Oracle 系目标不显示该开关；已存在的大写目标对象不会自动改名，需重建
  目标表并重新基线；业务 SQL 查询时也必须带同样的引号大小写。ETL 任务不在此开关范围内。
- 不开该开关时，表名仍可用「表名映射」逐表改名（映射后按目标方言折叠）；字段名不支持
  逐列改名，列清洗规则只改值、不改列名。
- 2.4.5 起写 Vastbase 等目标前会读取目标目录里的实际列名并按其拼写生成 SQL，所以目标
  折叠成小写后同步仍能正常写入，只是对象名不是大写。
- 要保留大写（非 Oracle / 达梦目标）：先在目标库关闭对应的折叠参数（通常是初始化或
  重启级设置），再重建这些表并重新同步；已经建好的小写对象不会自动改名。

## 全量到底做了什么

- 全量不是只对比或只取水位：Jync 会按源端当前数据整表读取，并按主键 upsert 到目标，
  同时记录水位；之后的增量只读水位之后的变化。水位是全量的副产物，不是它的全部。
- 目标已有数据时：默认不清空目标表，已有行会被更新、缺失行补齐；目标多出来的行不会
  自动删除，需要开启「同步删除」并让删除比对跑一次（受行数上限约束）。
- 支持「值未变化」守卫的方言会把未变化的行报成 0 行，但源端读取和语句执行仍然发生，
  不要把它当成一次免费的校验。
- 目前没有「只设置水位、不重新拷贝数据」的选项；切换策略后的基线一定会重新读一遍表。

## 切换策略后为什么又全量了

- 日志 CDC（binlog / redo / WAL / 变更表 / YStream）和增量轮询都是「增量」，区别是变更来源：
  CDC 读日志，轮询按游标列查新数据。
- 增量轮询必须有一个游标水位才能只读变化。从日志 CDC 切到增量轮询、或者新建一个轮询任务时，
  进度里没有轮询游标，所以第一轮会先做一次全量来建立水位，之后才按游标增量。这不是数据
  意义上的「首次同步」，而是为新策略重建基线。
- 这次全量按主键 upsert，默认不会清空目标表（除非勾选「全量前清空目标表」）：已有行会被
  更新，缺失的行会被补上，通常正好修复切换前发现的数据缺口。
- 基线完成后不会再全量；只有游标被清空或不可用、目标表被重建、或者再次切换策略 / 游标列
  时才会重新全量。
### 从轮询切回日志 CDC

- 切换时 Jync 会停掉消费者、清空轮询游标和旧位点，重建一次基线，再从新锚点回放；不会
  沿用轮询之前的旧位点。
- 切换前确认：
  - 表是基表并且有主键。视图没有行变更事件，无主键表难以定位 UPDATE / DELETE，这两类表
    不适合 CDC（通常也是 binlog 阶段数据缺失的原因），应继续使用轮询。
  - 需要传播删除时先开启「同步删除」：新基线完成后会做一次删除比对，清掉轮询期间源端已
    删除、目标还留着的行；否则这些行不会自动消失。
  - binlog / 归档日志的保留期要长于基线耗时；否则锚点日志被清理，切换后会立即报位点过期。
  - 切换会再全量一次（按主键 upsert，默认不清空目标表），大表安排在低峰期。
- 操作顺序：停任务 → 确认 `binlog_format=ROW`、复制账号权限、日志保留期 → 需要时开启
  同步删除 → 切换策略 → 启动 → 观察 CDC 延迟与两侧行数。

## 实时同步注意事项

- 写入按主键 upsert（at-least-once）：目标要有主键或唯一索引，否则重复批次会插入重复行。
- 全量与增量衔接：先定基线再滚动回放；停机期间的变更由位点或游标在重启后补齐。
- 删除：轮询看不到 DELETE；CDC 能回放，但目标行被手工删掉不会自动补回。
- DDL：默认不会自动改目标表结构；可在任务详情开启「CDC 自动补齐新增可空列」，仅自动执行只包含 `ADD COLUMN`、且所有新列可空、没有主键/唯一/自增/生成/引用约束的 ALTER；自动执行后仍会保留「补齐缺失数据」待办，等待人工回放，不会把缺口直接标成完成。其他 DDL 仍登记待办。缺列时 CDC 会裁剪该列并登记待办。一源多目标暂不自动执行。
- 位点或游标过期（binlog、归档、WAL 被清理）只能重建基线，会重新全量。
- 大事务在内存缓冲，上限 100 万行 / 512MiB，超限按非瞬态失败停车。
- 改同步策略、游标列或表清单会重新初始化对应表，先评估数据量再改。

## 全量加载性能调优

四个最常用的参数（application.yml 的 sync.*，改完重启）：
- sync.full-load-batch-size：默认 2000，建议 1000–5000。每批行数，越大提交越少，回滚与内存成本越高。
- sync.full-load-fetch-size：默认 2000，建议 1000–5000。源端结果集批量，大字段表调小。
- sync.full-load-parallelism：默认 2，建议 1–4。单表并行 worker 数，1→2 收益最大。
- sync.chunk-size：默认 100000，建议 5 万–20 万。每块行数，也是崩溃后最多重读的行数；0 关闭分块。
实测 1000 万行表全量约 40 秒（约 25 万行/秒）；表宽、索引、网络、目标库写入能力与并发负载都会明显影响结果。
先调并行度，再看批量；延迟创建二级索引通常仍有收益，但幅度以自己环境的吞吐日志为准。

## 吞吐日志怎么看

全量阶段每张表、每个 worker 打一行吞吐日志：
table=orders thread=full-load-orders rows=10000000 changed=10000000 elapsed=40.0s rows/s=250000.0 query=3% read=35% write=52% commit=10% batches=5000 rowsPerBatch=2000
- rows/s 就是这张表的同步速度。
- query%/read% 高：瓶颈在源库 SQL、游标与网络，先查源端。
- write% 高：目标库约束、唯一索引、触发器与大字段写入慢。
- commit% 高：提交太频繁，加大 sync.full-load-batch-size。
日志文件：./logs/jync.log（UTF-8），单文件 10MB、保留 30 天、总量 500MB。

## 同步速度与日志在哪里看

- 任务详情「性能」页：吞吐、延迟与失败曲线，按分钟采样，保留 7 天，可选 1 小时 / 24 小时。
- 任务详情「变更日志」：每轮同步的行数、游标值与成败，失败时看「详情」列。
- 「日志管理 → 系统日志」：应用运行日志，与 ./logs/jync.log 同源。
- 数据库性能页：会话数、QPS、缓存命中、锁等待与延迟，默认每 300 秒采样。
- 增量轮询每轮会打：Synced N row(s) (M changed) for 表 in window (...]。
metrics.enabled=false 只关闭采样，不影响同步链路。

## 调度与轮询间隔

- 全局默认 sync.poll-interval，默认 2000 毫秒；任务详情页可单独填轮询间隔或 Cron，留空继承全局值。
- 轮询间隔实际生效范围 200ms–24h；间隔模式错过的触发直接丢弃，下一轮会看到全部变化。
- 任务启动后立即跑一轮，再按调度运行；运行中的任务需先停止才能改调度。
- 「立即同步」不改变启用状态，从上次成功位置补跑；「停止同步」在下一个已提交批次边界安全退出。
- 日志 CDC 任务没有 Quartz 调度，页面隐藏调度卡片。
- 多实例用内存 Quartz 重建调度，实际执行由数据库租约互斥。

## Cron 表达式写法

Cron：同步任务用 Quartz 语法，6 段或 7 段（含年份）；ETL 与专属迁移用 Spring 6 段（无年份段），保存时 7 段会被拒绝，秒在最前，保存与启动时都会校验；填写后优先于轮询间隔。
- 0/2 * * * * ?　　每 2 秒
- 0 0/5 * * * ?　　每 5 分钟
- 0 30 2 * * ?　　每天 02:30
- 0 0 8 ? * MON-FRI　　工作日 08:00
按服务器时区求值，部署时固定 -Duser.timezone 或容器 TZ；错过的触发会在有空闲线程时补跑一次。

## 增量轮询与 CDC 批次参数

- sync.batch-size：默认 500，增量轮询与 CDC 回放的 JDBC 批次行数。
- sync.fetch-size：默认 1000，增量读取的结果集批量。
- sync.cdc.logminer.batch-size：默认 10000，LogMiner 单批行数。
- 真空闲休眠：LogMiner 与 SQL Server 默认 1000ms，PostgreSQL 500ms，最低 50ms；MySQL binlog 由服务端推送。
- sync.cdc.change-window-ms：默认 10000，CDC 变更日志按表聚合窗口，0 关闭。
- 单个源事务缓冲上限：100 万行 / 512MiB，超限按非瞬态失败停车。

## 连接池与并行度

- sync.max-pool-size 默认 5，是每个数据库连接池的上限。
- 全量 worker、增量轮询与 CDC 回放共用同一个连接池；并行度总和不要超过池上限，否则互相排队。
- 多个任务共用一个连接时同时跑满会抢占连接，建议错峰，或调大连接池后再调并行度。
- 单表并行对整数首列主键按区间均分，其他主键形状从源库有序键集采样边界；采样不被支持时自动退回顺序流式读取。

## 大字段与索引对写入的影响

- LOB/BLOB/JSON 等大字段会同时拖慢读取与写入，fetch-size 调小，避免整批数据长期占内存。
- 目标表二级索引越多写入越慢；首次全量可先延迟创建二级索引，全量完成后再建。
- 目标表有唯一索引时全量走 upsert 而不是 INSERT，吞吐约降到 INSERT 的 1/2.5。
- 列清洗会强制整列物化，单值超过 sync.lob.force-inline-max-bytes（默认 1MB）会明确报错。

## LOB 流式与临时溢写

- 大文本/二进制不再整块读入堆：不超过 sync.lob.inline-max-bytes（默认 64KB）内联，
  更大的值流式读写；关闭 sync.lob.streaming-enabled 即回到整块读取。
- CDC 事务缓冲中超出的值先落盘到 sync.lob.spill-dir（默认临时目录下 jync-lob），
  按事务边界删除；单事务溢写上限 spill-max-bytes（默认 1GiB），残留超过
  spill-retention-hours（默认 24 小时）的目录在启动时清理。
- 单批累计字节超过 batch-max-bytes（默认 16MiB）先提交；单个值超预算时独占一批。
- 主键、游标与列清洗用到的列必须内联，上限 force-inline-max-bytes（默认 1MB），
  超出会报错并指出表与列。
- PostgreSQL/openGauss 源读取期间保持只读事务，fetch size 才生效，代价是源库 vacuum 被推迟。

## 行数核对与内容校验

- 任务详情「核对行数」默认“快速核对”：读数据库统计信息，标记为估算，不做全表扫描。
  精确核对走后台任务，源/目标共享总预算 `sync.row-count-job-timeout-seconds`（默认 600 秒），
  某一侧超时会单独标记；可停止，停止会 cancel 正在执行的 COUNT(*) 语句。一次精确批量
  最多 50 张表，更多时用快速核对或逐表重新核对。
- 内容校验默认每 15 分钟抽样 50 行，只报告差异、不修复目标；要求表有主键、走增量策略、首次全量已完成。
- 参数：sync.content-audit-interval-ms（900000）、sample-size（50）、max-rows（2000000）、
  max-tables-per-cycle（1）、timeout-seconds（60）。
- LOB、JSON、XML 等不可比列默认排除。
- openGauss / HANA / Oscar 等无法判断“行是否真的变化”的目标，回放窗口会按目标返回的 affected rows 累计，变更行数可能偏大；需要精确变更数的场景请用 CDC。

## 大字段与漂移比对（LONGTEXT / LONGBLOB）

- 预检里的「这些目标列不参与漂移比对」**不是不支持同步**。LONGTEXT / LONGBLOB、CLOB / BLOB、
  JSON / XML 等大字段在全量加载、轮询增量和 CDC 中都会正常读写；大值按
  `sync.lob.streaming-enabled` / `sync.lob.inline-max-bytes` / spill 配置流式处理。
- 这条提示只表示它们不参与**内容级漂移比对**：数据库对这类类型通常没有可靠的 `=` / `<>`
  比较，Jync 无法安全地逐列判断两侧内容是否相同。
- 受影响的是内容审计、全表比对，以及 upsert 的「行没有变化就不重写」判断；行数核对、
  主键匹配和其他普通列比对不受影响。表里只要存在不可比列，该行回放会退化为重写，
  数据仍然正确。
- 预检文案会按捕获方式区分：CDC 项目明确说明每次变更都会写入目标，不提示更新时间列；
  全表比对说明每轮会重读并写入这些列；只有轮询模式才提示「只改大字段可能检测不到，建议
  配置更新时间列（如 updated_at）」。
- 轮询模式下，如果只改了 LOB 列、游标列没有变化，漂移审计可能发现不了这次更新；要覆盖
  这类更新，请使用 CDC（MySQL binlog、PG 逻辑解码、Oracle/达梦 LogMiner、SQL Server CDC、
  崖山 YStream），或给表增加 `updated_at` 这类会变化的游标列。
- 目前不做 LOB 的逐字节/哈希比对；需要发现只修改大字段的更新时，用 CDC 或增加
  `updated_at` 这类会变化的游标列，不要在回答里承诺已经支持哈希比对。

## 同步可靠性与超时参数

- sync.safety-lag-ms：默认 1000，从时间戳水位回退一点，避免时钟乱序提交的源事务被跳过；实际下界按严格 `>` 读取，因此会多回退 1 秒，避免恰好落在边界上的行被漏掉。
- 数值/自增游标没有安全余量：按已提交的最大值推进，先取号后提交的更小 id 可能落到窗口之外；行数审计能兜底但可能滞后，建议改用时间游标或日志 CDC。
- sync.row-count-audit-interval-ms：默认 60000，定期核对两侧行数；缺口不超过
  row-count-repair-max-rows（默认 1000）时按键补齐，更大则清游标重灌该表。
- sync.max-unindexed-scan-rows：默认 100 万；游标列无索引且超过此规模时停掉 COUNT(*) 探测并
  告警，避免每轮全表扫描。
- 超时：query-timeout-seconds 300（数据面语句）、copy-timeout-seconds 1800（整表复制）、
  metadata-query-timeout-seconds 60、pool-wait-ms 10000（池满退让到下一轮）。
- 停机：shutdown-wait-ms 默认 20000，超时后不再等待并回滚未提交批次。
- 卡住判定 stuck-threshold-ms 默认 30 分钟；租约 lock-ttl-ms 5 分钟，持锁节点宕机后其他节点接管。
- reconcile-interval-ms 默认 30000（30 秒），多实例下定期核对谁该跑哪个任务。

## Oracle CDC 配置

1. 源连接用途选「源」，类型选 Oracle。
2. 在连接表单填写挖掘账号（minerUsername）与密码；该账号需要 LogMiner 权限。
3. 源库必须为 ARCHIVELOG，开启库级 SUPPLEMENTAL LOG DATA，选中的每张表都要有
   SUPPLEMENTAL LOG DATA (ALL) COLUMNS。
4. 保存后到任务详情页把同步策略切换为日志 CDC，选择表并启动。
5. Oracle 11g+（12c 起的挖掘角色检查按版本自适应）。
6. 多租户 CDB 环境必须在 CDB$ROOT 里执行 DBMS_LOGMNR，并确保挖掘账号有权访问目标 PDB 的
   归档日志；归档保留建议 ≥ 24 小时。
6. 行外 LOB 在 LogMiner 里只有占位符：按 sync.cdc.logminer.missing-lob-policy 处理——
   skip（默认）跳过这类变更并限流告警，目标保留旧值，需要对该表补一次全量追平；
   fail 在首条此类变更处停车且不推进位点。状态面板可见 skippedMissingLob 与 missingLobByTable。

## MySQL CDC 前提

- MySQL 5.7+；log_bin=ON、binlog_format=ROW、binlog_row_image=FULL。若运行中改成 MINIMAL/NOBLOB，Jync 会在行事件处停车并提示改回 FULL，而不是跳过；MySQL 8.0.20+ 的 binlog_transaction_compression（zstd）已支持。
- 账号需 REPLICATION SLAVE 与 REPLICATION CLIENT（MariaDB 10.5 起该权限名为 BINLOG MONITOR）。
- 任何 MySQL 线协议内核（MariaDB、TiDB、OceanBase、GBase，或声明为 MySQL 的自定义连接）都能
  通过 MySQL 系列校验，但连接测试必须真的解出一条 binlog 事件才算通过。
- binlog 保留 ≥ 24 小时；位点是 binlog 文件 + 偏移（含 GTID）。
- 每个任务用 server_id = sync.cdc.server-id-offset（默认 612000）+ 项目 ID；
  同一源库上不要让两个实例的同一项目 ID 同时消费，否则会互相踢下线。

## 达梦 CDC 前提

- DM8；ARCH_MODE=Y 归档模式；RLOG_APPEND_LOGIC=2（redo 含完整旧镜像）。
- 账号可读 V$ARCHIVED_LOG 并可执行 DBMS_LOGMNR。
- 只能挖已归档文件，归档切换周期就是延迟下限，建议定时执行 ALTER SYSTEM SWITCH LOGFILE。
- 位点取 MAX(NEXT_CHANGE#) 的 SCN。

## PostgreSQL 逻辑解码前提

- PostgreSQL 11+，以及 openGauss、GaussDB、Kingbase、Highgo、Vastbase、Oscar、RaseSQL、
  SeaBoxSQL 等 PG 系内核（声明为 POSTGRES 的自定义连接也走同一系列校验，连接测试会实测复制槽）。
- wal_level=logical；账号需 REPLICATION（openGauss 不要求）；openGauss 建议 enable_slot_log=on。
- 每张表 REPLICA IDENTITY 不能是 NOTHING。
- 位点是 LSN；复制槽滞留 WAL 超过 4GiB 会在面板告警。

## SQL Server CDC 前提

- SQL Server 2012+；库级执行 sp_cdc_enable_db，并逐表执行 sp_cdc_enable_table（消费者会一直
  轮询，直到捕获实例出现；只开库级不会捕获任何行）。
- SQL Server Agent 必须运行（捕获作业归它管）；Azure SQL Database 没有 Agent，不支持本模式。
- 账号加入 cdc_Admin / cdc_reader；缺少 VIEW SERVER STATE 时连接自检只能给警告，不阻断。
- 位点是 LSN；真空闲时默认每 1 秒轮询一次。
- 捕获实例创建后新增的列不在 `@captured_column_list` 里；Jync 发现变更表缺绑定的列时会停车并提示重建捕获实例，不会静默跳过这张表的全部变更。

## 崖山 YStream 前提

- YashanDB 23.4+；ARCHIVELOG 且连接主库；走 YStream，不走 LogMiner。
- 账号需 CREATE SESSION、SELECT ANY TABLE、YSTREAM_CAPTURE、SELECT ANY DICTIONARY。
- 每张选中的表执行 ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS；缺补充日志的 DML 会静默不进流
  （DBA_LOG_GROUPS 里没有类型为 ALL 的组就是没开）。新建的表尤其容易漏，加组后同一条插入立刻可见。
- 必须显式选表；每个实例最多 32 个 YStream 服务。
- 位点是五元组（commitScn / instanceId / groupLsn / groupOffset / batchRowId）。Jync 自己存的
  位点是权威：服务端 checkpoint 只反映它最近一次周期落盘，不能用来决定从哪继续。
- 回滚到 savepoint 的行不会出现在流里（服务端解析 redo 时已过滤，23.4.7 实测不发 savepoint 事件）；
  带 chunked LOB 的行无法拼装，会让任务进入停车状态，需排除该表或改用轮询。

## CDC 位点、租约与多实例

- 统一流程：先记位点 → 分块全量基线 → 长驻线程连续回放。
- 多实例用数据库租约互斥；持锁节点宕机后，其他节点通常在 60 秒内接管。
- 位点过期（归档或 binlog 已被清理）需要重建基线。
- 目标表被删时默认只重建并重灌这张表，位点不动；连续修复超过 3 次会停车。
- 源库执行 DDL 导致目标缺列时，流内裁剪并登记待办，任务不停摆。
- `Error retrieving record: Can not read response from server... before connection was unexpectedly lost`：来自 MySQL Connector/J 的流式查询读取，不是 binlog 解码；通常是源端连接被 `KILL`/重启、网络或代理超时，或目标写入太慢超过源库 `net_write_timeout`。先看 MySQL 错误日志/`Aborted_clients`，调大 `net_write_timeout`、代理空闲超时，或降低批量/并行度；替换 jar 只有在旧进程仍在查询时才会直接触发。全量分块与单流全量都会在断连时换新连接重试：有主键的分块保留已提交部分，无主键单流会从整表重来；无主键大表建议加主键或改用可切片方案。
- 被跳过的 DDL 会登记为待办（变更日志「在目标库执行 DDL」）；开启自动补齐可空列后，安全子集会在目标自动执行，执行成功后仍保留「补齐缺失数据」待办，失败也回到待办；缺口回放要求任务先停止。DROP TABLE / DROP VIEW 默认写成 `IF EXISTS` 再执行；Oracle / DM / 崖山会自动去掉该子句，并把「对象本来就不存在」当作完成，所以目标已经删过表不会让待办永远红灯。

## CDC 停摆与重建基线

- 需要重建：位点已过期（日志被归档/清理）、消费者多次失败后被停住、目标表丢失或结构被破坏。先看任务状态与变更日志运行态，再做前提自检，排除连接与权限问题。
- 重建做什么：停止消费 → 删除位点 → 清空各表进度与全量检查点（重新按首次加载处理）→ 摘掉错误状态 → 任务启用时立即重启并全量加载；任务已停用时只重置，下次启用再全量。
- 全量加载按主键 upsert：源端仍存在的行会新增或覆盖，默认不会先 TRUNCATE；若任务显式开启「全量前清空目标表」，重建后的首次加载会清空目标，回答时要说明这一点。目标表多出来的行不会自动消失。
- 要顺带清掉源端在停摆期间删除的行：先给任务开启「同步删除」再重建，顺序反了不会补历史删除。它按主键比对，只删不加；日志 CDC 下只在基线加载完成后执行一次，之后的删除由日志流自身同步。表要有主键，行数不超过 sync.full-compare-max-rows（默认 20000），超过时需把 sync.delete-compare-max-rows 调到覆盖行数（0 表示关闭分页比对）；列清洗改写主键的表只能内存比对。
- 重建后仍对不上：报表/汇总表若由 Jync 之外的作业生成，任何基线都不会对齐；再查目标端触发器、删除比对上限与源端未提交事务。
- 多节点：必须在持有消费者租约的节点执行，其他节点拒绝是保护。大表全量很重，避开业务高峰。

## 外部基线接入（跳过首次全量）

- 入口在任务详情，仅管理员可见；任务必须已停止、数据库单目标、日志 CDC。Redis 缓存目标与一源多目标不支持。助手只能解释，不能执行。
- **位点是什么**：源端日志流里的一个位置（MySQL 是“binlog 文件名 + 字节偏移”），表示“这份目标数据已经包含到这一刻为止的变更”。Jync 不跑全量，启用后只回放该位置之后的变更；位置之前的行必须已经由你的 dump/备份工具写进目标。它不是行数、不是目标库位点，表进度也不会回填。
- **前提**：先做一致性 dump，并在同一时刻记录源位点。常见做法：`mysqldump --single-transaction --source-data=2`（旧版本 `--master-data=2`）把 `CHANGE REPLICATION SOURCE`/`CHANGE MASTER` 的 FILE/POS/GTID 写进 dump 文件头；Percona XtraBackup 看 `xtrabackup_binlog_info`；或用 `FLUSH TABLES WITH READ LOCK` → `SHOW MASTER STATUS` → dump → `UNLOCK TABLES`。目标和位点必须来自同一份 dump。
- **MySQL 示例**：文件名 `mysql-bin.000123`（只填 `SHOW BINARY LOGS` 的 File 名，不填路径）；位点 `4`（只填数字，范围 4 到该文件大小；不知道精确位点时用文件开头 4；位点=文件大小表示已到文件末尾，后续事件在下一个文件，Jync 会接着读）；GTID 可选，如 `3E11FA47-71CA-11E1-9E33-C80AA9429562:1-100`，Jync 会核对它是源库 `@@GLOBAL.gtid_executed` 的子集，即使填了 GTID，文件与位点仍会校验。
- 不知道精确位点时：选一个**确定早于 dump**、且仍在 `SHOW BINARY LOGS` 里的文件，位点填 `4`（文件开头）；会重放一些 dump 已包含的变更，带主键表由 upsert 吸收。不要选晚于 dump 的点。
- **MySQL 常见错误**：用 dump 之后的 `SHOW MASTER STATUS`（会漏掉 dump 期间的变更）；把位点填成行数、文件大小或 event 序号；文件和位点不是同一次查询的结果；binlog 已被 `PURGE`（报文件不存在）；GTID 与位点不是同一时刻。
- PostgreSQL：填 `SELECT pg_current_wal_lsn();`（形如 `0/16B6C50`）或 slot 的 `confirmed_flush_lsn`；项目 slot 必须在 dump 前创建，填写值不能早于 `restart_lsn` / `confirmed_flush_lsn`，否则 dump 与 slot 之间有空档，会被拒绝。
- SQL Server：填 `SELECT CONVERT(varchar(32), sys.fn_cdc_get_max_lsn(), 1)`（`0x` 开头、共 22 字符）；选中表必须已有 CDC 捕获实例，位点不能晚于源库当前最大 CDC LSN。
- Oracle / 达梦：填 SCN 数字（Oracle `SELECT CURRENT_SCN FROM V$DATABASE`；达梦查询当前 SCN），不能早于源库仍可挖掘的最老 redo，也不能晚于当前 SCN。
- 崖山：填现有唯一 YStream 服务的 `RESTART_POSITION`，必须完全一致。
- 接入会清空旧位点、旧全量 checkpoint 和错误帽；校验或清理失败不会把基线标成完成。外部基线覆盖的表在 `project_table_status` 中显示 `COMPLETED` + `externalBaseline=true`，累计行数与 `fullLoadPercent` 为空；这不是 `NOT_STARTED`，不代表没有同步。旧的全表配置会从外部基线范围补出表名。之后要重灌请用「重建基线」；接入前请确认目标数据与位点一致，Jync 不做全量校验。

## 数据没有继续同步怎么查

源端新增或修改了数据、目标端没有变化时，先按任务类型分开排查，三类任务不是一回事。

- 实时同步：看两件事——Jync 是否看到变更、是否写成功。最近变更时间与最近成功写入时间都停滞，
  问题在读侧（任务未运行、CDC 停摆、轮询没有触发、位点或时间列前提不满足）；看到了变更但
  最近一次写入失败，问题在写侧（看失败记录里的失败端与错误摘要）。CDC 模式再看消费者是否在
  本节点、位点是否过期；轮询模式看游标列在源端是否真的发生变化（源端更新没有触碰游标列时，
  轮询不会看到新行）。
- ETL：先确认任务启用、Cron 有效、没有正在运行，再看最近结果与连续失败次数。连续成功但
  写入行数一直是 0，说明运行正常、取数范围或断点水位没有新数据（源端确实无新增，或范围条件
  与断点冲突），不是同步故障。
- 专属迁移：看最近一次运行是否仍在 RUNNING，当前索引与已写文档数是否在增长；INTERRUPTED
  表示进程重启中断；失败索引数大于 0 时以运行消息里的摘要为准。
- 在悬浮助手里问同一句话，助手会按资源类型一次调用 diagnose_project、diagnose_etl_task
  或 diagnose_migration 取齐证据，再给结论、建议与可执行项；启动、停止、立即运行、重建
  基线这类动作以确认卡给出，点「确认执行」才生效。工具进度行分别显示等待进行、正在进行、
  已完成或失败，需要连源库的前提自检与行数核对在完成前不应当作结论。
- 三类任务都不把进程日志文件发给 AI；诊断依据是任务状态、运行历史与产品元数据。

## ETL 任务与变量

ETL 任务用客户自己的取数 SQL 复制结果行，支持覆盖、追加与范围覆盖。SQL 里可用
`${date}`、`${datetime}`、`${yesterday}`、`${month}`、`${year}`、`${taskName}`、`${targetTable}`、
`${runIndex}` 等内置变量，也可在任务上自定义 `name=value` 变量。未知变量会在保存、预检、运行时分别报错。

`${runIndex}` 是该任务已成功运行的次数：首次运行是 0，每次成功加 1，失败不计数（重试仍读同一个值）；
修改取数 SQL 或变量定义后从 0 重新开始。适合按运行次数推进窗口的取数，
例如 `WHERE slot = ${runIndex}` 或 `WHERE ts >= DATE_SUB('${datetime}', INTERVAL 1 HOUR)`。

## ETL 运行方式与断点续跑

- 写入方式：覆盖（清空重写）、追加、范围覆盖（按范围条件删除后写入）。
- 全量完成后写检查点，中断后从断点续跑；修改取数 SQL、变量或目标表会清空检查点。
- 运行中的任务不能编辑；预检会校验 SQL 结构、目标表与列、变量引用。
- 同源同目标的表可组成批次，共享连接与调度。
- 多个 ETL 任务同时运行由执行租约互斥，同一任务不会并发跑两遍。

## ETL 注意事项

- 取数 SQL 由客户完全掌控；新建目标表时结果列就是目标列。目标表已存在时只写结果列与目标列的交集：目标缺少的列跳过并告警。目标的自增 / GENERATED ALWAYS / 计算列不显式写入，由目标生成；把这类列用作 UPSERT 主键列会失败。
- 覆盖：写入方式的 DELETE 与第一批数据在同一事务里提交；首批写入失败会回滚，旧数据保留。取数结果为空时会显式提交 DELETE，目标被清空。中断后重跑从头开始。追加：不删除，配 resume_key_column 可断点续跑。
- 范围覆盖：按 range_condition 删除后写入；带断点续跑时失败不重复删除，跑完清检查点。
- UPSERT：按 upsert_key_columns 判断存在则更新；每次都要重放全部行，不认断点。
- 改取数 SQL、变量、目标表、范围条件、断点列或 upsert 键都会清空检查点。
- MongoDB 原生源的断点键不能混合类型或全为 null，否则预检直接拒绝（跨 BSON 类型排序会跳数）。
- 运行中不能编辑；同一任务由执行租约防重入，同源同目标可组成批次共享连接与调度。
- preSql 在目标表创建后、写入方式的 DELETE 与取数之前执行，并立即提交；从检查点续跑时不会重放。APPEND 成功后保留检查点，所以 preSql 通常只在第一次运行执行；OVERWRITE 每次运行都会清检查点，preSql 每次都会执行。postSql 在写入完成后执行；两者共用变量替换与脚本校验。
- 源连接与目标连接不能指向同一个物理库：同类型、同 host/端口/库/schema 的两条连接记录也会被拒绝（同服务器不同 schema 允许）。
- 目标 schema/表名按目标方言加引号：MySQL 反引号、PostgreSQL/Oracle/DM 双引号、SQL Server 方括号；Oracle 系按大写折叠，PostgreSQL 保留配置大小写。ETL 任务不读取实时同步里的「保持源端大小写」开关，Oracle / 达梦目标仍按大写建表写入。
- MongoDB 原生源按采样文档推断目标列；运行中遇到采样未覆盖的字段会中止并报错，不会静默丢字段。Oracle 预检中的 ORA-00942 只给警告：既可能是表不存在，也可能是账号看不到。

## 结构同步

结构同步对比源与目标的表、索引、视图、存储过程/函数，生成可审阅的 DDL 后执行。跨数据库
类型时按方言族做类型映射、函数转换与标识符引号处理；执行前逐条展示差异。

## 专属迁移概览

「专属迁移」不走 JDBC，与实时同步、ETL 互不影响，也不占用 JDBC 连接池。
- 三种链路：Elasticsearch / OpenSearch → Easysearch；MongoDB → MongoDB；MongoDB → KingbaseES。
- 复制的是运行那一刻的整个索引或集合，不是持续同步；重复运行 OVERWRITE 可让目标收敛。
- 支持手动或 cron 定时、运行中取消，并保留最近 50 次运行历史。

## 专属迁移创建步骤

1. 同步任务 → 专属迁移 → 管理连接，先建专属连接：ES/OpenSearch/Easysearch 填基础地址，
   选 Basic 或 API Key；MongoDB 填连接串与数据库名。
2. 新建迁移任务，选择迁移类型，勾选索引或集合（支持 glob，系统索引默认排除）。
3. 目标命名选与源同名、加前缀或加后缀；写入方式选删除后重建、追加或已存在则失败。
4. 先点「预检」，通过后保存，再「立即运行」或启用 cron。

## 专属迁移预检与运行

- 预检检查源与目标可达、目标版本兼容性、_source 是否开启、mapping 是否兼容、目标是否已存在。
- 模式没有匹配到任何索引会判为失败，避免 0/0 的假成功。
- ES 迁移复制 settings（清理后）、mappings 与 aliases，按 _id / _routing 用 _bulk 写入文档。
- MongoDB → KingbaseES 会采样文档推断列再建表；目标缺列、类型不符、_id 无唯一索引都会在预检报出。
- 进程重启后遗留的 RUNNING 运行记录会标记为 INTERRUPTED。

## 专属迁移注意事项

- 复制的是运行时刻的快照，不是持续同步；源端之后的变更需再跑一次（OVERWRITE 会让目标收敛）。
- 一次最多 200 个索引或集合；glob 不匹配任何对象判为失败，不做 0/0 的假成功。
- ES → Easysearch：复制清理后的 settings、mappings 与 aliases，按 _id / _routing 批量写入；
  _source 被关闭的索引在预检就拒绝。批次默认 1000 条 / 10MB，scroll 5 分钟。
- MongoDB → MongoDB：默认批 1000 条 / 8MB，可分别开关集合选项、索引与文档复制；
  写模式、命名与 continueOnError 按任务配置。
- MongoDB → KingbaseES：按采样文档（默认 200 条）推断列，嵌套对象与数组按 JSON 文本存；
  _id 必须能作主键，目标缺列或类型不符在预检报出。
- 预检通过再运行；进程重启遗留的 RUNNING 会标记 INTERRUPTED，需要人工重跑。

## Redis 缓存目标

- 项目的目标类型选 Redis；Redis 是独立目标（单机或 Cluster，不支持 Sentinel），不是 DatabaseType。
- 只支持日志 CDC，保存时会拒绝 POLLING / FULL_COMPARE。
- 投影按项目配置：keyPrefix（默认 jync:<项目ID>:）、valueFormat（JSON 或 HASH）、
  ttlSeconds、excludedColumns、maxValueBytes、maxCommandsPerTransaction。
- 语义是基线 + 回放 + 前缀清扫重建；超限行跳过并计数，不截断、不停机。

## 列清洗规则

- 在写入目标库之前处理字符串列，源库数据不受影响；按「表 + 列」配置，大小写不敏感。
- 同一张表内按保存顺序执行；全量、增量轮询与 CDC 回放走同一套规则，数字与日期原样通过。
- 常用规则：TRIM、UPPER、LOWER、BLANK_TO_NULL。
- 需要参数的规则：STRIP_PREFIX、STRIP_SUFFIX、REGEX_REPLACE（正则按 Java 语义在保存时校验）。

## 掩码与哈希

- MASK_PHONE、MASK_ID_CARD、MASK_BANK_CARD、MASK_NAME、MASK_EMAIL 保留格式，长度基本不变。
- HASH_SHA256 输出固定 64 位十六进制摘要，确定性，可带盐；盐只防跨系统碰撞，不能防字典攻击。
- 目标列长度不足 64 会在写入时报错。
- 掩码与哈希只改写目标库；主键、游标或核对列被改写后，无法再用目标值回源定位。

## 连接与驱动

除 GBase、神通、GaussDB、SAP HANA、ClickHouse、Hive、Impala、TDengine、Elasticsearch、
MongoDB 外，驱动随包内置。这些厂商需要自备 JDBC jar 并在连接表单里填写 jar 路径；驱动类名
不同可在同一张卡片覆盖。

## TDengine 与时序数据

- 只走 WebSocket（6041）；需自备官方 `taos-jdbcdriver-3.10.0-dist.jar`（`-dist` 包含 Netty
  等依赖，普通 jar 会缺类），连接必须写明数据库名。
- 实时同步里只作「源」，没有日志 CDC：只有轮询 / 全表比对，看不到窗口内被覆盖或删除的中间
  过程。开启「同步删除」且表在行数上限内时，按主键比对仍可清理目标端多余的行。
- 主键与游标：无声明主键时 `ts` 唯一；超级表按 `ts + 主键 + TAG` 定位，子表不单独列举。
  游标列要手工指定（通常是 `ts`）；时间戳水位用 `LAST(列)`（TDengine 拒绝 TIMESTAMP 的
  `MAX`/`MIN`），整型 / 字符游标仍用 `MAX`。
- 写入 TDengine 只能走 ETL，且目标表必须事先存在（普通表或子表），或在「前置 SQL」里自建；
  Jync 不会自动建表，超级表不能作目标，ETL 也不支持 UPSERT 模式。
- 范围覆盖的删除条件必须带 `ts` 时间窗，否则 TDengine 拒绝整轮删除（该轮不会写入任何行）；
  「覆盖」模式的整表删除可用。
- 不要把 `DECIMAL` 列用作 ETL 目标列：3.3.6 服务端配 3.10.0 / 3.8.4 驱动会把十进制按文本
  发出、服务端按二进制解码，写入错误数值且不报错。建表请改用 `DOUBLE`（有精度损失）或换用
  已修复的服务端 / 驱动组合。
- `BINARY` 与 `VARCHAR` 在驱动层不可区分，按文本读取（非 UTF-8 字节会损坏），二进制保真请用
  `VARBINARY`；`BIGINT UNSIGNED` 映射为 `DECIMAL(20,0)`，`GEOMETRY` 映射为 `VARBINARY`。
- 没有二级索引（索引只能建在 TAG 上）；游标列不是主键前导列时，每次增量读取都是全表扫描。
  没有事务，失败重跑可能留下部分数据。
- 版本基线：服务端 3.3.6 社区版 + 驱动 3.10.0，其他组合未验证。

## 用户角色与权限

- 内置 admin（系统管理员）、ops（运维）、view（访客），初始密码都是 123456，首次登录强制改密。
- admin 拥有全部资源与系统配置；ops 可查看全部资源，写操作只限自己名下的资源，不能进入
  系统配置、外部认证、告警、审计、AI 配置与配置包；view 只读。
- 所有写操作都是 POST，要求管理员或运维；访客构造请求也会被拒。
- 归属按创建者账号记录；升级前创建的资源没有归属，运维只读且不能在新任务里引用。

## 外部认证与统一认证

「配置管理 → 外部认证」支持三种方式，同一时刻只生效一种：
- 账号密码：门户提供登录 API，Jync 能访问该 API，支持表单或 JSON 与字段映射。
- 网关头：网关已完成登录并注入身份头；只信任 app.security.trusted-proxies 内的直接对端。
- 票据：登录中心签发一次性 ticket；只接受 http(s)，不跟随重定向。
外部用户只保留会话，不写入 app_user，没有本地密码与改密入口。

## 忘记密码与本地应急登录

- 忘记密码无法找回：删除 app_user 对应行后重启会重新种入初始账号。
- 本地账号默认保留为应急入口 /login/local；关闭前必须先启用并配置好外部认证。
- 关闭本地登录后 POST /login 也会被拒绝。
- 恢复：以 JYN_RESET_LOGIN=true 启动一次（等价 app.login.reset-on-startup: true），
  修好外部配置后去掉变量并改回 false 重启。

## HTTPS 方式一：反向代理终止 TLS

- Jync 只监听本机 8080，由 Nginx、网关或云负载均衡终止 TLS，明文只存在于代理到 Jync 的内网段。
- 代理必须转发 Host、X-Forwarded-For、X-Forwarded-Proto。
- 设置 app.security.trusted-proxies（或 JYN_TRUSTED_PROXIES），否则代理来的请求会被当明文直连。
- 打开 app.security.require-https（或 JYN_REQUIRE_HTTPS=true），会话 Cookie 设 Secure：
  JYN_COOKIE_SECURE=true。
- 在「配置管理 → 系统信息」确认「连接加密」显示 HTTPS 已加密。
- 不要开启 server.forward-headers-strategy=framework，否则客户端可伪造来源地址。

## HTTPS 方式二：应用内证书

- 进入「配置管理 → 系统配置 → TLS 证书」，选择「PEM 证书」或「密钥库（PKCS#12/JKS）」。
- PEM 要求证书链按服务器证书在前排列，私钥为无口令的 PKCS#8/PKCS#1，并会校验私钥与证书配对。
- 密钥库的口令必须同时能打开库和私钥；单文件上限 5MB，证书过期或未生效会被拒绝。
- 填写 HTTPS 端口（默认 8443），可勾选把 HTTP 重定向到 HTTPS；保存后必须重启才生效。
- 也可以用配置文件 app.tls.keystore-path / app.tls.pem-cert-path 或 JYN_TLS_* 接管，页面变为只读。
- 启动失败时可用 JYN_TLS_ENABLED=false 救回实例。

## 强制 HTTPS 与安全响应头

- app.security.require-https 默认 false；开启后判定为非 TLS 的请求直接返回 403。
- 两个例外：不带 Cookie 的 GET /login（健康探针），以及尚无可用 HTTPS 端点时的回环应急通道。
- 直接暴露 8080 又没上传证书时不要开启强制 HTTPS，否则除本机回环外全部 403。
- HTTPS 生效时响应带 HSTS；所有响应带 CSP、Referrer-Policy、Permissions-Policy 与 X-Frame-Options。
- 嵌入 iframe 需要 HTTPS，并同时设置 JYN_COOKIE_SECURE=true 与 JYN_COOKIE_SAME_SITE=None。

## 元数据库迁移与配置包

- 元数据默认存在内置 H2 文件；换 MySQL / PostgreSQL / Oracle 时，只改 SPRING_DATASOURCE_* 不够。
- 流程：旧实例「配置管理 → 系统配置 → 配置包」输入口令导出（可勾选同步进度与游标）→ 停旧实例
  → 配置新库的 SPRING_DATASOURCE_* 与 SYNC_CRYPTO_* → 启动新实例 → 导入配置包，无需再重启。
- 目标库的配置与状态表必须为空；用户按用户名合并，导入后必须至少还有一个管理员。
- 包用 AES-256-GCM 加密，口令至少 8 位，单包 ≤ 20MB；包里携带旧加密密钥，导入后请销毁。
- 不进包：变更日志、审计、性能采样、运行历史、分块断点、TLS 证书。
- 导入会重新分配项目 id，且不恢复「基线已完成」标记：CDC 项目首次启动会重新做一次全量加载。
  PG 复制槽名只含 project id、YStream 服务名带随机后缀，换库后旧槽/旧服务必须手工清理，
  否则新实例可能接到仍在运行的旧槽上；迁移前先停掉旧实例的 CDC。

## 启动 jync.jar 或 Docker 时指定外部元数据库

不设置 `SPRING_DATASOURCE_*` 时，Jync 使用内置 H2（默认 `./data/jync.mv.db`）；生产环境建议换成 MySQL / PostgreSQL / Oracle。

### jync.jar 启动

先在目标数据库创建空库和账号。MySQL 示例：

```sql
CREATE DATABASE jync CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

启动前设置环境变量，再用 JDK 17 运行：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://db:3306/jync?useSSL=true&serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME='jync'
export SPRING_DATASOURCE_PASSWORD='change-me'
export SPRING_DATASOURCE_DRIVER_CLASS_NAME='com.mysql.cj.jdbc.Driver'
export SYNC_CRYPTO_PASSWORD='a-long-random-secret'
export SYNC_CRYPTO_SALT='0123456789abcdef'  # 偶数位十六进制
java -jar jync.jar
```

`SYNC_CRYPTO_SALT` 必须是偶数位十六进制字符串；示例值上线前要换掉。PostgreSQL 的 URL 例如 `jdbc:postgresql://db:5432/jync`，驱动为 `org.postgresql.Driver`；Oracle 可用 `jdbc:oracle:thin:@//db:1521/FREEPDB1`，驱动为 `oracle.jdbc.OracleDriver`。新库的表由 `ddl-auto: update` 自动创建；不是只改 URL 就会带上旧数据，已有 H2 数据要按上一节导出配置包再导入。`SYNC_CRYPTO_PASSWORD/SALT` 必须显式设置并长期保持不变；`/app/data` 仍建议挂载，用于 TLS 证书和备份。

### Docker / Compose 启动

Jync 镜像只包含应用和 JDBC 驱动，不包含 MySQL / PostgreSQL / Oracle 数据库服务。仓库提供三个外部元数据库示例：

```bash
docker compose -f deploy/docker-compose.mysql.yml up -d --build
# 也可换成 postgres 或 oracle 示例
```

示例会同时启动数据库和 Jync，并已通过 `SPRING_DATASOURCE_*` 指向数据库；生产使用已有数据库时，可以删掉数据库服务，只保留 Jync 并改这些变量。若直接 `docker run`：

```bash
docker run -d --name jync -p 8080:8080 \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://db:3306/jync?useSSL=true&serverTimezone=Asia/Shanghai' \
  -e SPRING_DATASOURCE_USERNAME=jync \
  -e SPRING_DATASOURCE_PASSWORD=change-me \
  -e SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver \
  -e SYNC_CRYPTO_PASSWORD=a-long-random-secret \
  -e SYNC_CRYPTO_SALT=0123456789abcdef \
  -v jync-data:/app/data -v jync-logs:/app/logs \
  vfaner/jync:latest
```

修改 Compose 的环境变量后要执行 `docker compose up -d` 重建容器（`restart` 不会重新读取环境变量）。旧实例已有数据时，先在旧实例导出配置包，启动新实例后再导入；不要直接删除 H2 文件当作迁移。

## 备份、升级与数据目录

- 升级前备份整个数据目录（默认 ./data，容器内 /app/data），其中包含 H2 元数据库与 data/tls 证书。
- 元数据表结构由 ddl-auto: update 自动演进，不需要手工迁移。
- Docker 换镜像标签重建容器即可，数据卷不动；K8s 改组 kustomization 的 newTag。
- 停机期间源库的变更由游标或 CDC 位点在重启后自动补齐。
- 不要更换内嵌 H2 的版本（固定 2.1.214），否则旧库文件可能无法打开。

## 告警通知

「告警通知」支持同步失败、轮询卡死、项目未运行、CDC 停摆、数据漂移、连接健康、PG slot 积压、
DDL 被跳过（CDC 遇到无法自动执行的 DDL）、CDC 目标端已修复。
支持通用 JSON、钉钉、企业微信、飞书；可设置冷却时间与恢复通知。Webhook 地址加密存储。
默认拒绝指向本机或内网的 Webhook（alert.webhook.allow-private=false），发到内网聊天服务时再打开。

## 审计日志

「日志管理 → 审计日志」默认只增不删，任何账号都不能修改记录；管理员可以按日期选择性清理
（删除所选日期当天及之前的记录，今天的记录始终保留，因此不存在一次清空），清理动作本身会再记
一条审计（操作人、截止日期、条数），所以痕迹删不掉。
记录登录成功/失败与来源、任务与连接的新增/修改/删除、强制解锁、捕获策略切换、CDC 基线重建、
变更日志、审计日志与系统日志归档的按日期清理、专属迁移与 ETL 的写操作、配置包导入导出、诊断包导出、AI 助手提问等。
每条含时间、操作人、动作与详情，可按动作与用户筛选；合规审计时用它还原「谁在何时改了什么」。
「日志管理 → 变更日志」同样按日期删除（范围跟随当前筛选：项目 / 仅失败），未完成的 DDL 任务
属于待办不会被删除。「日志管理 → 系统日志」管理员也可按日期删除已滚动的归档文件（文件名带
日期），正在写入的 jync.log 与 .tmp 不受影响；这次清理同样会记入审计。
保留期默认：变更日志与审计日志各 180 天（sync.change-log-retention-days /
sync.audit-retention-days，每小时自动清理，设 0 关闭）；系统日志滚动保留 30 天
（logging.logback.rollingpolicy.max-history，另有单文件 10MB 与总量 500MB 上限）。

## 诊断包与技术支持

「系统配置 → 诊断包」下载一个 zip：程序版本、配置结构、同步进度、最近变更、操作审计与日志尾部，
另附 manifest.json 记录每个条目的字节数与 SHA-256。密码、加密密文、含凭据的连接地址、游标值
与 CDC 布局签名不会包含；自由文本经过脱敏。整包上限 10MB，超限返回 413 并提示减少日志或最近记录。
包内仍可能含业务信息，请按敏感文件处理；报障时附上诊断包、复现步骤与任务名。

## Prometheus 指标接入

- 设置 metrics.prometheus-token（或环境变量 JYN_METRICS_TOKEN）后，可抓取
  GET /api/metrics/prometheus；token 留空时端点关闭。
- 认证用 Authorization: Bearer <token>，这是唯一门槛，抓取端不需要 Jync 会话。
- 应用内采样与 Prometheus 端点互不影响；关闭采样不影响同步链路。
- 内网没有 Prometheus 时，任务详情「性能」页与数据库性能页已覆盖日常观察。

## 检查更新与一键升级

- 「系统信息」与导航圆点会检查新版本：GitHub 优先，超时或失败自动改查 Gitee（app.gitee-url）。
- 成功结果缓存 10 分钟、失败 1 分钟；版本号相同也会比对安装包摘要，只有证明不同才提示。
- 纯内网两个平台都访问不了时，在「系统配置 → 更新检查」关闭，导航圆点与升级提醒随之消失；
  配置文件里显式赋值后该开关变为只读。
- 一键升级下载新版 jync.jar（Gitee 附件超 100MB 时按 part 分片下载）并校验，替换当前文件，
  旧文件保留为 jync.jar.bak；随后需重启生效，重启会中断运行中的同步任务，建议选空闲时段。
- 容器部署不能替换镜像内的 jar：拉取新镜像标签后重建容器，数据卷（/app/data）保持不动。
  AI 助手的 runtime_info 能回答当前版本、最新版本与部署方式，但不会代执行升级或重启。

## 常见排查

- 任务显示运行但进度不动：看详情页的活动周期与「距上次进展」分钟数，超过阈值会标记卡住。
- 行数对不上：先在预览页核对两侧 COUNT(*)，再检查游标列是否单调、是否有未提交事务。
- CDC 延迟上涨或停摆：检查源库日志保留时间、消费者位点与网络；位点过期或错误状态停住时按「CDC 停摆与重建基线」处理。
  面板的延迟是「最近一个已应用变更在源库产生的时刻」到本机处理时刻之差，源库空闲不会增长；
  SQL Server CDC 按提交 LSN 查 cdc.lsn_time_mapping 采样，映射行已被清理时保持上一次采样。
- 重建基线后两侧仍对不上：重建本身不删行；要清掉源端已删除的行需开启「同步删除」并满足主键与行数上限；由 Jync 之外作业生成的报表表不归基线管理。
- 连接测试失败：确认端口、账号权限与防火墙；自备驱动时确认 jar 路径与驱动类名。
- 全量慢：先看吞吐日志的 query/read/write/commit 占比，再按对应方向调参。
- 页面 403：多为开了强制 HTTPS 但请求仍是明文，检查代理头与 trusted-proxies。
- 保存证书后 HTTPS 页面打不开：连接器只在启动时加载证书，重启 Jync 并确认 HTTPS 端口已放行。
- 开启 SSO 后登录循环：/login/local 本地登录仍可用；配置改坏了可用 JYN_RESET_LOGIN=true 启动
  恢复，恢复后立即改回。

## AI 配置

「配置管理 → AI 配置」维护模型端点，同一时刻只有一个启用；密钥与数据库密码一样加密存储，
编辑时留空表示保留原密钥。
- 协议：OpenAI 兼容（自建与多数国产端点）、Anthropic、Ollama（兼容层）、Ollama（原生接口）；
  保存前可「探测连通性」，尽早发现填错。
- 参数：Base URL、模型名、最大 token（长存储过程要留足）、超时秒数、深度思考（默认跟随；
  本地推理模型不关会把预算花在思考上，正文可能为空）。最大 token 与深度思考主要影响
  「存储过程转换」；运维助手每次回答使用固定的短回复预算，不读这里的最大 token 设置。
- 用途一：存储过程转换的 AI 草稿。用途二：悬浮运维助手，只在存在启用供应商时出现。
- 探测与转换都会请求你配置的端点，转换还会带上存储过程源码；内网或有合规要求的部署请优先接
  本地/自建端点。没有启用供应商时不产生任何外部请求；sync.ai.enabled=false 彻底移除该功能。

## 存储过程转换与 AI 草稿

在项目详情进入「转换复审」：列出源库的视图、存储过程与函数，先做机械转换（类型、函数与引号按
方言族映射），逐条对比原始定义与自动转换结果。
- 可以手工编辑并「保存为覆盖」，覆盖优先于自动转换，也可删除覆盖恢复自动转换；失效覆盖（对象
  已不在源库或已取消选中）仍保留，对象回来后重新生效。
- 启用 AI 供应商后可「用 AI 起草」，模型给的只是候选：要人工核对、可编辑保存，不会自动进入
  目标库；同一对象已缓存草稿时优先复用，避免重复计费。
- 「在目标库做语法检查」会用临时名创建后删除，只证明目标库接受这段 DDL，不证明行为一致；
  游标语义、异常处理、NULL 拼接等仍需用真实数据核对。
- 转换本身不写目标库；结构同步执行的是自动转换结果或你保存的覆盖。

## AI 运维助手能查什么

- 内嵌悬浮窗面向管理员与运维，可查项目运行状态、进度行数、最近失败、1 小时吞吐与延迟。
- 取证与执行优先级：实时状态/历史/指标/待办 → 先调用工具；产品规则 → search_docs；报错 →
  search_logs；文档和日志不足 → search_instance_code；最后才用通用经验（标明推断）。用户要求
  改设置时先调用对应 control_* / update_* 生成确认卡，不要只教用户去页面操作。
- 可查单个同步任务的详情（project_status）：两端连接的名称与类型、捕获策略、选中表数量、
  同步数据 / 结构 / 删除等开关、下次计划时间，以及运行状态与当前周期进度。
- 可查任务内每张表的同步状态（project_table_status）：COMPLETED 首次全量已完成并在增量
  （Jync 全量为 fullLoadPercent=100；外部基线表没有此值）；IN_PROGRESS 正在装载/可续传，
  或游标被清空、等待下一次全量
  （可能没有 fullLoadRowsCopied）；INCOMPLETE 首次全量未完成，或游标被清空、等待下一次
  全量；轮询任务在两次周期之间也可能暂时如此，不要只凭它断定任务已停止，要结合 running /
  租约 / 下次计划时间；NOT_STARTED 已勾选但还没有任何进度。每行同时给出 rowsSyncedTotal（累计同步行数：
  全量按复制行数，增量按实际变更，不是目标当前行数）、最近同步时间（全量进行中是最新检查点
  时间）、本次全量已拷贝行数与百分比、检查点更新时间；incrementalState 为 RUNNING /
  PAUSED / ERROR / LIMITED / WAITING，是项目级运行状态与表游标能力推断，不代表 CDC 单表
  已追平。可按表名子串或状态过滤；表很多时
  先给各状态计数，返回结果有上限并标记截断。任务在运行不代表每张表都已完成，未开始的表
  没有进度行，不能只凭任务状态推断。selectionKnown=false 表示旧配置没有明确的表清单，
  摘要只能基于已有进度行，回答时应说明「未开始表无法完整列出」。
- 可查本实例应用日志（search_logs）：按关键词、级别与滚动文件搜索，返回最近的匹配行；用于解释报错栈和“为什么失败”。
- 可查当前部署实例的代码/配置线索（search_instance_code）：搜索 jar 内 application.yml、i18n、AI 文档、模板、静态 JS 与 com.qqmu.jync 的 class 常量（错误消息/SQL/属性名）；不读取业务数据，也没有 .java 源码。
- 可查某个任务尚未收尾的 CDC DDL 待办（project_ddl_tasks）：最多 20 条，给出待办 id、表、
  状态（PENDING/FAILED）、是否已执行/已回放、目标 SQL、错误与受影响表；用于解释「为什么目标
  缺列 / 还需要回放」。
- 可查变更日志（CDC）运行态：连接状态、位点、最近错误、跳过 DDL 与列裁剪等计数，并区分
  「本节点持有消费者」与「消费者在其他节点或未运行」。
- 可对单个项目做变更日志前提自检（归档日志、binlog 格式与保留期、补充日志、复制/挖掘权限、
  server_id 冲突等），逐项列出不通过的原因；该自检会连接源库，一次对话最多执行 2 次，
  只能对自己有编辑权的任务执行（与页面上的自检按钮同一权限）。
- 可查当前生效的告警（同步失败、轮询卡死、CDC 停摆、数据漂移、连接不健康、PG slot 积压、
  DDL 被跳过），以及 ETL 任务的启用状态、最近运行时间、成功与否与结果行。
- 可诊断单个 ETL 任务（diagnose_etl_task）：启用、Cron 是否有效、是否在运行、两端连接名、
  最近结果与最近 20 次运行的行数和时间，用于区分「运行正常但没有新数据」与「一直失败」；
  结果里批次编号非空表示它是批次成员，与同批次任务共享连接与调度。
- 可列出专属迁移任务（list_migrations），并诊断单个任务（diagnose_migration）：迁移类型、
  两端连接名、启用状态、最近运行状态、当前索引、读写文档数与失败索引数。
- 可查最近 1 小时到 7 天内的失败记录（默认 24 小时，最多 20 条）：告警只看仍生效的问题，
  这里能看到凌晨失败、早上已恢复的短暂故障；可按任务过滤。
- 用户描述同步任务问题（停摆、数据对不上、反复失败、变慢）时，助手用一次 diagnose_project
  取齐任务状态、最近失败、生效告警、CDC 位点与关键开关（captureStrategy、syncDeletes、
  truncateBeforeInitialLoad、fullCompareMaxRows、deleteCompareMaxRows）；同时给出「最近一次
  变更」与「最近一次成功写入」的时间，用来区分没看到变更（读侧）与看到却写失败（写侧）。
- 回答按「结论 → 依据 → 建议 → 可执行项」组织：可执行项对应运行态操作，以确认卡呈现，
  每张卡是一个可选方案，用户点「确认执行」才会生效；改任务配置、删目标表、重跑外部报表
  作业这类助手不能执行的动作会写成人工步骤，不会伪装成确认卡。
- 排障建议顺序：先看告警与最近失败，再看任务状态，再看变更日志运行态，必要时做前提自检，
  最后对照本知识库的前提与已知限制。数据对不上时，优先核对同步删除开关、全量比对上限与
  目标表是否由外部作业生成。
- 可查连接的数据库指标（1h / 24h）、连接列表与项目列表，并核对单表两侧行数。
- 可检索本知识库回答产品用法，并按描述生成新任务草稿，由用户确认后预填表单。
- 可查三类日志与指标的实际保留期（log_retention），回答「日志保留多久」「旧记录为什么没了」；删除日志本身仍是页面上的手工操作，助手不能代删。
- 可查当前运行版本、最新发布版本与更新状态、是否为容器部署（runtime_info），回答「是不是最新版」「怎么升级」「Docker 还是 jar」；升级、拉镜像与重启进程仍需人工执行。

## AI 运维助手的操作与边界

- 可执行运行态操作：启动 / 停止同步任务、立即同步一次、重建 CDC 基线、启用 / 停用 /
  立即运行 ETL 任务、启用 / 停用 / 立即运行 / 取消专属迁移任务。助手只生成「确认卡」，
  不会直接执行；点「确认执行」后服务端按当前账号的角色与资源归属再校验一次——运维只能
  操作自己名下的资源，管理员可操作全部，访客不显示助手。重建基线会触发一次全量加载；
  多节点部署时必须在持有消费者租约的节点执行。没有可用 Cron 的迁移任务不能启用，已在
  运行的迁移不能重复立即运行，没有运行中的迁移也取消不了——这些前置条件在生成卡片前检查。
- 可调整任务级性能参数（update_project_params）：批量大小（1–20000）、全量并行度（1–8）、
  轮询间隔（≥200 毫秒，仅轮询 / 全表比对策略）、CDC 空闲轮询（≥50 毫秒，仅 LogMiner /
  PostgreSQL 逻辑复制 / SQL Server CDC / YStream）。任务必须已停止，运行中先生成「停止
  任务」卡；只对调用者有编辑权的任务生成卡片，管理员可操作全部。卡片逐项列出原值 → 新值，
  点「确认执行」后才落库，下次启动生效，并写入审计（字段与新值）。
- 可批量调整游标列（update_project_cursor）：columns 是「表名 → 游标字段」，空字段表示
  恢复自动识别；priority 是项目级有序优先级（最多 20 个）。只适用于增量轮询任务；日志
  CDC、全表比对和 Redis 目标不使用游标列。改动过游标列的表下次同步会重新全量，任务必须
  先停止；单张确认卡最多 20 项改动，更多请分多次。
- 可调整调度 Cron（update_project_schedule）：Cron 优先于轮询间隔，传空字符串会清空 Cron
  并回到轮询间隔；只适用于轮询 / 全表比对，日志 CDC 与 Redis 目标没有 Quartz 调度。
  Cron 按服务端 JVM 时区解释，页面显示的「下次触发」也是该时区。
- 可调整连接驱动参数（update_connection_params）：先用 connection_details 查看当前
  extraParams 与使用该连接的任务；只能增删改 extraParams（如 connectTimeout、socketTimeout、
  批处理与 keepalive 开关；set 设置、remove 删除，合计最多 20 项），不能改主机、端口、库名、账号、密码、自定义 URL、驱动 jar 或
  连接类型。连接可能被多个任务共用，调用者必须能编辑该连接且所有使用它的任务都已停止；
  若连接还被其他账号的任务使用，只有管理员可以通过助手修改。卡片列出旧值→新值和受影响任务。
- 管理员可查看 / 调整 10 项系统性能参数（system_params / update_system_params）：批量、
  fetch、chunk、chunk-target-bytes、自适应 chunk 与重试次数（均带 sync. 前缀，值为字符串；
  set 设置、reset 恢复 application.yml 基准）。`sync.batch-size` 在本节点保存后对运行中
  CDC 消费者的下一批立即生效，其他节点约 30 秒收敛，轮询任务下一轮生效；其余参数对后续
  同步轮次生效。只管理员可用。恢复默认回到各节点自己的 application.yml，多节点部署应保持
  基准一致。连接池、超时、线程、保留期、比对 / 删除语义、CDC 事务上限、TLS / 认证 / 密钥等
  仍不开放。
- 操作范围有边界：删除、上述字段以外的改配置、连接身份字段、系统设置、外部认证、告警、
  AI 配置、用户与审计都不在助手的操作范围内；不能执行 SQL。表选择、捕获策略、同步删除 /
  清空、列清洗 / 掩码、DDL 覆盖与 application.yml 系统参数（连接池、chunk、超时、
  max-concurrent-tables 等）仍不能通过助手修改，只给属性名、建议值与「改配置并重启」的
  说明。变更日志（CDC）任务不支持「立即同步」，这类任务
  只能用重建基线，助手会直接说明，不会给出点了必然被拒绝的卡片。
## AI 运维助手的数据边界

- 工具结果是查询那一刻的快照，不是持续刷新：回答会带上时间或距今分钟数，需要最新状态时
  重新查询。前提自检一次对话最多 2 次、行数核对最多 3 次，到达上限时助手会说明并建议到
  页面自行执行或另开对话。
- 所有数字必须来自工具结果，不编造；工具失败时说明原因，不用相同参数反复重试。

## ClickHouse 作为数据源

- 只作「源」，没有日志 CDC：实时同步走轮询或全表比对；开启「同步删除」且表在行数上限内时按主键比对清理目标端多余行。
- 驱动需自备 `clickhouse-jdbc`（应用不内置）。无主键表按 LIMIT/OFFSET 分页全量，重试可能重复写入，建议声明主键或改用有唯一值的游标列。
- 游标列建议用 DateTime64 或数值列；时间水位按墙钟字面量比较，与服务器 / JVM 时区无关（2.4.4 起修复）。
- 目标端自动建表的类型映射：String → TEXT、Decimal(p,s) → NUMERIC(p,s)、DateTime64 → TIMESTAMP、Nullable → 可空列。

## 多实例部署注意

- 跨主机多副本必须使用外部元数据库，H2 文件库只支持同一台主机上的多进程共享。
- 多实例需显式设置相同的 SYNC_CRYPTO_PASSWORD / SYNC_CRYPTO_SALT，否则各自生成的密钥不同。
- Quartz 使用内存存储，节点启动时按数据库里的启用标志重建调度；CDC 与同步执行由数据库租约互斥。
- 应用内 TLS 配置是节点本地的，多副本建议由 Ingress 终止 TLS，或改用配置文件模式。
- 两个实例连接同一源库时，相同项目 ID 会算出相同的 MySQL server_id；演示实例请先停 CDC，
  或修改 sync.cdc.server-id-offset。

## 报错日志速查

用户可以把完整报错直接粘贴到右下角「AI 辅助」提问，不需要先整理。长日志会保留开头与结尾，中间出现省略标记时说明日志被截断；关键异常位于中间时，请把那一小段再单独粘贴一次。排查时先找第一条 ERROR 与 `Caused by`，不要把 Jync 回显的 SQL 当成根因。

- `Batch write failed ... Column "X" not found in upsert attrno state initialization`：海量 / openGauss 目标的带引号列名大小写与目标目录不一致。2.4.5 起按目标实际列名生成 SQL；升级重启后仍报错时，检查目标列是否被 `lower_case_column_names` 存成了另一种大小写。
- `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE ... was aborted: UPDATE with where condition is not yet supported`：海量不接受“值未变化”守卫。2.4.5 起原生写入去掉该守卫，带安全余量的增量窗口改用 UPDATE + INSERT 模拟。
- `unsupported literal: TIME'...'`：达梦 LogMiner 的 ANSI TIME 字面量；2.4.5 已支持 `TIME` / `TIMESTAMP WITH TIME ZONE` 等形态，升级并重启 CDC 即可。
- `Skipping INSERT/UPDATE/DELETE row on schema.table: <原因>`：CDC 主动跳过该行，目标可能缺少这条变更；按冒号后的原因处理，不要把它当成网络抖动反复重试。
- `OUT_CLOB` / `OUT_BLOB` 或状态页「跳过缺失 LOB 变更」：达梦 redo 没有行外 LOB 正文。设置 `LOGMNR_PARSE_LOB=1` 和 `LOB_MAX_INROW_LEN=8000` 后 8KB 以内可挖掘；更大或行外 LOB 需要轮询 / 定期全量。
- 「无主键表,仅基线播种」：要么源表确实没有主键，要么元数据表名大小写与实际不一致导致没读到主键。先核对源表主键定义，再看报错里的 schema 与表名大小写；真正无主键的表 CDC 只能做一次基线播种，后续变更不会跟踪。
- 源 `DATETIME`、目标变成 `DATETIME(6)`：MySQL → MySQL 的同族类型映射。2.4.5 起保留源精度；已存在的目标列不会自动缩精度，重建目标表或结构变更时才会按新规则生成。

## 达梦 LogMiner 跳过事件与 LOB

状态页的「跳过不支持事件」是 CDC 跳过的**总数**，其中包含「跳过缺失 LOB 变更」，两者不要相加；例如显示 5875 与 2035，表示其中 2035 条是 LOB，剩余约 3840 条是其他原因。

- 先看 WARN 日志 `Skipping <OP> row on <schema>.<table>: <原因>`。`unsupported literal` 是某种字面量没有解析；`column ... not in binding` 是 redo 列与当前元数据不一致；`where missing PK column` 是 redo 的 WHERE 没有主键。
- LOB 表会显示每张表的跳过数。`LOGMNR_PARSE_LOB=0`（默认）时 CLOB/BLOB 以 `OUT_CLOB` / `OUT_BLOB` 出现；执行 `CALL SP_SET_PARA_VALUE(1,'LOGMNR_PARSE_LOB',1)` 与 `CALL SP_SET_PARA_VALUE(1,'LOB_MAX_INROW_LEN',8000)`（必要时重启）后，8KB 以内的 LOB 可以进入 CDC。
- 超过 8KB 或真正行外存储的 LOB 内容不在归档 redo 里，标准 LogMiner 无法还原；把这几张表拆成轮询任务 / 定时 ETL，或把大 LOB 拆表、只保留引用。
- `sync.cdc.logminer.missing-lob-policy=skip` 会保留目标旧值并计数；`fail` 会在第一条缺失 LOB 处停住，适合不能接受静默偏差的场景。
- LogMiner 的 `PURGED`（ORA-01291）详情带 resume SCN 与 oldest still-available redo：最老 redo 早于 resume SCN 却仍缺文件，是归档空档/文件被删/其他 thread 或 resetlogs 分支，调大保留期无效，先查 `V$ARCHIVED_LOG` 连续性；最老 redo 晚于 resume SCN 才是保留期不足。`V$ARCHIVED_LOG` 中 `DELETED=YES` 的旧行不再参与选文件或计算下限。

## MySQL 自增同步到达梦

- 达梦 8 目标：**整数类型且属于主键**的自增列使用原生 `AUTO_INCREMENT`（实测 8.1.2.128）。目标字典会显示自增；源端显式 ID 可以直接写入，无需 `SET IDENTITY_INSERT`；计数器自动跟随最大显式值（包括批量 INSERT 和 MERGE），全量后不需要推进序列。显式 0 会被当作“生成值”，与 MySQL 默认行为一致。
- 非主键自增列、或类型不是整数（例如 Oracle 系来源的 NUMBER 自增）仍使用 `SEQ_<表>_<列>` + `TRG_<表>_<列>` 触发器模拟，目标字典不显示“自增”属正常。旧版/既有表不会被自动转换，需要原生属性时重建目标表并重新基线。
- 达梦不允许修改 `AUTO_INCREMENT` 列（`试图修改自增列`，错误 -2664），因此这类列的类型变更会失败并留在变更日志；需要先重建目标表（或改由人工用目标端允许的方式调整），不要反复重试 MODIFY。不要建议改成达梦原生 IDENTITY——显式 ID 插入会被拒绝。
- 目标没有对应 SEQ/TRG 时检查：任务是否开启「同步结构」；目标表是否手工预建且结构同步关闭；源端 MySQL 的 AUTO_INCREMENT 是否被元数据读到；是否是旧版本/旧基线。需要重灌时用「重建基线」。
- 达梦目标默认把未加引号的标识符折叠为大写。若要求与源端（如 MySQL 小写）一致，打开任务详情「同步选项」里的「目标表名/字段名保持源端大小写」：之后按源端/映射里的拼写加双引号建对象并写列，业务 SQL 也要带同样的引号大小写。该开关只对 Oracle 系目标显示并生效、默认关闭，已存在的大写对象不会自动改名，需重建目标表并重新基线；不要建议改达梦全局参数来转小写，容易生成另一个带引号的小写对象。

## LogMiner ORA-01291 反复重建仍失败

- 不要先归因保留期：如果归档保留 3 天、基线只跑几十分钟，保留期不是原因。`ORA-01291: missing logfile` 表示覆盖 resume SCN 的某个归档文件不在或不可访问。
- 如果部署的构建只从 `DEST_ID = 1` 选归档，而本地归档实际在 `DEST_ID = 2`（1 号目的地是备库/远程），Jync 就看不到并会报缺文件。当前实现会读取所有 `DEST_ID`，按 thread+sequence 去重并优先本地副本；部署此类构建后仍需确认文件真实存在。
- 现场检查：`V$ARCHIVED_LOG` 的 `DEST_ID`、`DELETED`、`STATUS`、`SEQUENCE#` 是否连续、相邻 `NEXT_CHANGE#` 是否等于下一个 `FIRST_CHANGE#`；并确认存在 `FIRST_CHANGE# <= resume AND NEXT_CHANGE# > resume` 的行。没有这行说明锚点未被归档覆盖（归档缺口或 resetlogs/incarnation 变化），重建基线也无法恢复。RAC 每个 thread 都必须连续。
- 当前实现会在基线完成时检查 anchor 是否被归档或 CURRENT redo 覆盖；不覆盖会在标记基线完成前直接报“源端归档缺口”，避免先标完成、稍后才 ORA-01291。
- 处理：从备份恢复缺失归档，或对确认不需要的已删除 stale 行执行 `CROSSCHECK` + `DELETE EXPIRED` 清理控制文件记录，再重建。日志确实丢失时无法增量恢复，只能重新全量并保证后续保留期。
- 如果报错是 `LogMiner cannot add archive file '<路径>' (missing, deleted or not locally accessible on the source)`，那是 Jync 已经把要加的具体文件路径报出来了：按这个路径检查 `DEST_ID`/`STANDBY_DEST`（备库/远端不可读）、`DELETED`/`STATUS`、文件是否在本地；不要只看 `V$ARCHIVED_LOG` 的 SCN 是否连续。

## 目标端列名大小写与类型精度

- 海量等 PG 系目标开启 `lower_case_column_names` 后，带引号建出的列可能存成小写；Jync 2.4.5 起写海量前会读取目标目录的实际列名，按实际大小写生成 INSERT / UPDATE / DELETE，不需要改库参数。
- 达梦源表名是小写时，LogMiner 元数据若被强制转大写会读成“无列、无主键”；2.4.5 已改为按事件中的实际大小写读取，失败再回退大写。
- MySQL → MySQL 的 `DATETIME` / `TIME` 保留源列精度，`TEXT` / `BLOB` / `JSON` / `BINARY` 保留原类型；跨库源仍按更宽精度建目标，避免丢微秒。
- 这些类型变化只影响新建的目标表；已有目标表不会自动 ALTER，需要重建目标表或等对应列发生结构变更。

## Docker 部署与 ARM64

- 官方镜像支持 **linux/amd64（x86_64）** 与 **linux/arm64（aarch64）**。`aarch64` 就是 ARM64，不是第三种架构；Docker 平台名统一写 `arm64`，32 位 ARM 才是 `arm/v7`。
- 在 `uname -m` 输出 `aarch64` 的机器上直接运行 `docker pull vfaner/jync:latest`，Docker 会自动选择 arm64 镜像；不要加 `--platform linux/amd64`，否则会走 QEMU 模拟，出现 `exec format error` 或明显变慢。
- 银河麒麟 V10 aarch64（内核 4.19）可以运行，但需要实际安装 Docker / containerd；只有内核不等于有 Docker。Podman 也可直接运行同一镜像。
- Docker Hub 不可达时改用 `ghcr.io/vfaner/jync:latest`；完全离线可在一台机器 `docker save` 后拷贝 `docker load`，或直接使用 `jync.jar` + JDK 17 aarch64。
- 麒麟 / SELinux 环境下目录挂载用命名卷，或给 bind mount 加 `:Z`；否则容器可能没有写权限。
- 默认账号 `admin / 123456`，首次登录强制修改密码；数据与日志分别挂载 `/app/data`、`/app/logs`。

## 一源多目标（fan-out）

- **适用**：日志级捕获项目（MySQL binlog、PostgreSQL 逻辑解码、Oracle / 达梦 LogMiner、SQL Server CDC、崖山 YStream）。轮询 / 全表比对没有 fan-out 运行器；Redis 缓存目标不能作为 fan-out 主目标。
- **怎么建**：新建或编辑任务时，在「目标数据库」一个框里多选——第一枚标签是主目标，其余是额外目标。目标列表变更要求任务已停止；新增目标会清空共享锚点，下次启动对所有目标重跑基线。
- **数量**：默认最多 20 个（1 主 + 19 额外）。系统参数 `sync.fanout.max-targets`（2–100）可放宽，管理员也可以让 AI 运维助手改；调整只影响之后的保存，已在跑的项目不会被停。
- **基线**：只读源库一遍，行数据经中转分发给所有目标；无主键表跳过；每个目标各自建表。基线完成后等待各目标确认快照，最多 5 分钟；超时后继续启动，未确认目标不写「基线完成」进度，其 spool 保留快照和后续事件，恢复后追平；spool 达 2GB 或坏记录会停下该目标，下次启动要求重建基线。
- **DDL**：默认不会自动执行（与单目标一致，登记为变更日志待办）；单目标可开启「CDC 自动补齐新增可空列」，一源多目标仍要求人工执行。管理员在变更日志点「在目标库执行」时，语句会按各目标的 schema / 引号 / 表名映射改写后依次应用到**每个目标**，任一目标失败会在任务上标出目标名并保持可重试；随后的「补齐缺失数据」也回放到每个目标。跨库族目标语法不同时需要分别手工调整。
- **自动同步新增表**：开启后，运行中新建的表按引擎自动加入——MySQL binlog 在启动/重连时检测；PG、LogMiner、SQL Server、YStream 每分钟扫描。Oracle 新表自动补 `ALL COLUMN LOGGING`（账号需 ALTER 权限）；达梦由系统参数覆盖；SQL Server 自动创建捕获实例并沿用已有实例的角色；YStream 把新表加入运行中的服务并先补补充日志。额外目标在该表第一笔变更到达时自动建表并重灌。
- **目标自愈**：额外目标缺**表**时自动按源端结构重建、重灌并重放暂存的变更；缺**列**等其它结构漂移不会自动补齐，该目标 worker 会停止、积压增长，人工补齐后自动重放；积压达到 spool 上限或遇到坏记录时按致命错误处理，需要重建基线。
- **事件中转**：任务详情「同步选项 → 事件中转」二选一：
  - 本地队列（默认）：写在 `data/fanout/<任务 id>/<目标 id>.log`，单目标上限 2GB，零外部依赖；超限或读到无法解码的记录时该目标本轮停止重试并写 `.failed` 标记，下次启动要求重建基线。
  - Redis Stream：直接选一个已配置的**单机** Redis 目标（复用缓存投影的连接与加密口令）。每个项目一条 `jync:fanout:<任务 id>:events`、每个目标一个消费组，按事务 ACK，worker 重启先重读 pending；`XACK` 失败会让该目标从 PEL 重放，不会假装确认。本次启动要跑基线时，新建消费组从流尾创建，避免重放历史；正常恢复仍从流头重放积压。集群 Redis 目标不支持；默认不裁剪，用 `XINFO STREAM` 监控。可选 `sync.fanout.redis-max-len`（0=关闭）设近似 MAXLEN；开启后只有所有活跃目标的确认位点都追上最后一条已发布事件时才裁剪，慢目标未确认时不会裁剪，因此不会因裁剪丢未消费事件。
  - 删除项目、切回本地队列或更换 Redis 目标时，Jync 会删除整条 stream；只移除部分目标时会 `XGROUP DESTROY` 对应消费组。删除是尽力而为，Redis 不可达时要手工清理。
- 多实例 / 跨节点部署必须用 Redis（或让所有节点共享 data 目录），否则节点切换后本地 spool 里未确认的事件新节点读不到。
- **性能口径**：源端只有一条日志流、一次基线读、一次目录扫描、每表一次元数据读取，与目标数量无关；目标写入按目标并行。成本在 Jync 与目标侧：每个目标一条 worker、一个连接池位、一份中转；20 目标请确认目标库 `max_connections`，并注意 spool 磁盘上限（每目标 2GB）。
- **位点展示**：项目详情「各目标位点 / 落盘积压」显示每目标的文件:偏移 / LSN / SCN、已应用事务数、worker 最近错误和积压字节。位点是展示与排查用的，按目标每秒最多写一次；恢复以中转的确认位点为准。积压只增且 worker 报错，说明该目标写不进去，先看错误里的表名/列名。
- **排查**：某个目标落后——看积压字节与 worker 错误；所有目标都落后——看源端日志/网络与捕获错误；新增目标后没数据——确认任务停过并重建了基线；Redis 中转启动失败——确认选的目标可达且不是集群。
