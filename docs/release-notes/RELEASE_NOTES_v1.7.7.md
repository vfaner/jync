# Jync v1.7.7

预置数据库类型从 17 种扩展到 **26 种**：新增华为 GaussDB 与 8 个国产产品（中兴 GoldenDB、腾讯 TDSQL、阿里 PolarDB、万里 GreatDB、银联 UPDRDB、星环 KunDB、平安 RASESQL、东方金信 SeaboxSQL）。自定义连接可以声明 SQL 方言家族，同步前会预检目标库的标识符长度；结构同步关闭时改为按需读取元数据，降低每轮对源库字典的查询压力。同时修复外部驱动加载、失败隔离、约束开关与元数据路由等一批问题。

## 新功能

- **华为 GaussDB 预设**：与 openGauss 是两个产品，使用 `com.huawei.gaussdb.jdbc.Driver` 与 `jdbc:gaussdb://`，默认端口 8000，方言归入 PostgreSQL 系，upsert 使用 `ON DUPLICATE KEY UPDATE`。驱动需自备 `gaussdbjdbc.jar`。
- **中兴 GoldenDB / 腾讯 TDSQL（MySQL 版）/ 阿里 PolarDB（MySQL 版）**：三者均以 MySQL 协议对外提供服务，预置类型复用内置 Connector/J，并沿用 MySQL 方言与元数据读取；GoldenDB 默认端口 8880，TDSQL 与 PolarDB 默认 3306，按控制台实际端口修改即可。
- **万里 GreatDB / 银联 UPDRDB / 星环 KunDB（MySQL 协议）**：复用内置 Connector/J，连接参数与 MySQL 预置一致（默认 3306，`useAffectedRows=true`，用于区分新增与更新）；KunDB 预设只覆盖 MySQL 模式。
- **平安 RASESQL / 东方金信 SeaboxSQL（PostgreSQL 协议）**：复用内置 pgjdbc，默认 5432，元数据读取、DDL 与 upsert 走 PostgreSQL 同一条已验证链路。
- **自定义连接可选 SQL 方言家族**：`CUSTOM` 类型新增「方言家族」选项（通用 / Oracle / MySQL / PostgreSQL / SQL Server / DB2）。此前自定义连接一律按通用 ANSI 处理，upsert 退化为先 UPDATE 后 INSERT；选择一个家族后，DDL 生成、upsert 与元数据读取（视图、存储过程正文）都按该家族的方言执行。切换回预置类型时该字段自动清空。
- **同步前预检目标标识符长度**：按各产品自己的计量单位检查表名与列名（Oracle 11g 30 字节、12c 及以上 128 字节，PostgreSQL 63 字节；MySQL 64 字符、SQL Server 128 字符）。超限的表记录 ERROR 后从本轮跳过，其余表照常同步；此前这类表要等驱动在目标端报错，结构阶段还可能留下半提交的批次。
- **驱动卡片规则收窄**：只有 `CUSTOM`、驱动未随包内置的类型（GBase、神通、华为 GaussDB）以及 GoldenDB、GreatDB 在打开厂商驱动开关时显示「驱动 jar」卡片；MySQL、Oracle、达梦等已内置驱动的类型不再显示，避免误填覆盖。已有的自定义驱动配置在编辑页仍会展开并原样保留。
- **驱动类覆盖**：GBase、神通、GaussDB 等外部驱动类型可在「驱动 jar」卡片填写驱动类，留空则使用预设值。用于厂商 jar 的类名与预设不一致的场景，例如 GaussDB 的 `gsjdbc200.jar` 使用 `com.huawei.gauss200.jdbc.Driver`。
- 驱动 jar 路径现在也接受「存放 jar 的目录」，检测驱动类时会逐个扫描目录中的 jar。
- **系统信息页补充运行参数**：新增「全量加载性能」（全量批次、抓取行数、并行度）与「CDC 引擎参数」（重连退避上限、消费者巡检间隔、合并变更窗口、三种 CDC 的单批大小）两张卡片；并发参数卡片补齐连接池上限、行数审计间隔、查询/元数据超时、停机等待与调度对账间隔。

## 性能

- **结构同步关闭时按需读取元数据**：只读列、主键、自增与游标探测，跳过索引、注释等字典查询，每张表每轮少若干次元数据往返。外键字典在需要时仍会读取——目标方言无法关闭外键约束（Oracle 系、PostgreSQL 系），或项目关闭了「同步期间禁用目标约束」时，保持父表优先的加载顺序。
- **空闲轮次不再重写同步进度行**：游标没有推进时跳过进度表更新，长期运行的轮询项目不再每轮产生一次无意义的写库。

## 修复

- **外部驱动建池失败**：HikariCP 通过线程上下文类加载器解析驱动类，而用户 jar 由独立的 `URLClassLoader` 加载。创建连接池期间临时切换上下文类加载器，修复 GaussDB 等外部驱动在「测试连接」时报 `Failed to load driver class ... in either of HikariConfig class loader or Thread context classloader` 的问题。
- **保存时不校验覆盖类**：判断是否需要外部 jar 时改为以实际生效的驱动类为准。之前覆盖类不在 classpath、但预设驱动已内置时，保存可以通过，连接时才报 `error.driver.absent`；现在保存阶段即拦截。
- **类型切换残留**：切换数据库类型时清空驱动类覆盖，避免上一个类型填写的类名静默覆盖新类型的预设；jar 卡片隐藏时输入框与按钮一并禁用，残留值不会随表单提交。
- **结构同步关闭时外键顺序失效**：按需读取元数据曾把外键字典一并跳过，而数据阶段仍按外键数量排序——排序静默退化为原始顺序，子表可能先于父表加载，在无法关闭约束的目标（Oracle、PostgreSQL 系）上首轮全量即触发外键冲突。现在只在加载顺序真正重要时补读外键。
- **新预设的元数据路由**：GreatDB、UPDRDB、KunDB 走 MySQL 读取器，RASESQL、SeaboxSQL 走 PostgreSQL 读取器；此前退化为通用读取器，会丢失视图与存储过程正文。
- **自定义库测试连接的错误处理**：错误提示改用可翻译的 `error.custom.*` 文案键，类型为空时给出明确提示，而不是空指针异常。
- **单表运行时异常隔离**：数据阶段此前只隔离 `SQLException`，映射或方言缺陷抛出的运行时异常会中断后续所有表。现在单表失败记录后继续下一张，并显式回滚未提交批次；停止请求与租约丢失仍按原逻辑终止整轮。
- **SQL Server 约束开关逐表执行**：`NOCHECK` 没有库级写法，改为按表逐个执行；开关结束后重新启用约束失败时废弃物理连接，避免把约束仍处于关闭状态的连接放回池中。
- **索引重试队列非空时暂不写表快照**：快照一旦写入，后续轮次会把该表当作已完成而不再重试索引；现在等重试队列清空后再写。
- **自动建表后的延迟二级索引丢失**：结构同步关闭时按需读取的元数据不含索引；数据阶段自动建表虽然用完整定义发出了 CREATE，但随后「初次全量结束后建二级索引」拿到的是精简定义，索引既没创建也没进入重试队列，快照却已记为收敛。现在建表步骤把完整定义交给后续步骤。
- **标识符预检的计量单位**：MySQL 与 SQL Server 的官方上限按字符计，预检此前一律按 UTF-8 字节比较，22 个汉字就会被误判为超过 MySQL 的 64 上限并被拒绝同步。现在按产品各自的单位检查。
- **相同失败日志一分钟内只记录一次**：轮询间隔 2 秒，持续失败的表此前每天会写入四万多条相同记录。按项目/对象/变更类型/消息去重，60 秒窗口内的重复失败跳过；成功记录与消息变化的失败照常写入。

## 兼容性说明

- GaussDB 与 openGauss 的驱动、URL 前缀均不相同，请按实际产品选择预设，不要混用。
- TDSQL 与 PolarDB 的 PostgreSQL 版使用 PostgreSQL 预设；PolarDB 的 Oracle 兼容版是 PostgreSQL 协议产品，使用自定义类型、选择 PostgreSQL 方言家族并配上 `com.aliyun.polardb2.Driver`。
- GreatDB、UPDRDB、KunDB、RASESQL、SeaboxSQL 的预置只保证协议兼容（MySQL / PostgreSQL），直接复用内置驱动；若厂商要求使用自己的 jar，在连接表单里填写驱动类与 jar 路径即可覆盖。
- `CUSTOM` 仍是未预置产品的兜底。未选择方言家族时结构与数据都按通用 ANSI 处理，数据写入使用先 UPDATE 后 INSERT 的模拟 upsert；目标表没有主键时重试可能产生重复行，建议为参与同步的表建立主键。选择方言家族后行为与对应预置一致。
- 驱动卡片不再对内置驱动的类型显示。已有连接无需改动；需要换用厂商 jar 的 GoldenDB、GreatDB 连接，在卡片中打开厂商驱动开关后填写。
- Oracle 目标现在会在同步前拒绝超过 30 字节（11g）或 128 字节（12c 及以上）的表名与列名。此前这类表在目标端报 `ORA-00972`；如需同步，请缩短名称或调整表映射。达梦、崖山不受 Oracle 30 字节限制的影响。

## 测试

- 1735 个测试：1734 通过，1 个按环境条件跳过；`*IT` 需 Docker 另跑。

## 升级

- 替换 `jync.jar` 重启即可，表结构随 `ddl-auto: update` 自动演进（本次新增 `database_config.dialect_family` 列）；升级前备份 `./data`。
- 已配置的外部驱动连接无需改动：驱动类留空即继续使用预设值。
- 容器部署将镜像 tag 更新为 `jync:1.7.7`（Docker Compose 与 `deploy/k8s/` 清单已同步）。
