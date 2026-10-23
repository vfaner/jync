# Jync v1.7.8

大字段在任意两种受支持数据库之间都能正确同步：读取、绑定与 CDC 缓冲不再把整个值压在堆里；厂商专属类型补齐映射与取值归一化，跨方言建表不再产出非法类型名。另有安全加固与界面改进。默认行为与 1.7.7 一致或更安全，新增开关均可关闭回退。

## 修复

- **Oracle 目标绑定超过 32767 字节失败**：大文本/大二进制改走服务端临时 `CLOB` / `BLOB`，`MERGE`、`INSERT`、批处理与单行执行均适用。
- **空串写入 `NOT NULL CLOB` 报错**：空串绑定为空 `CLOB`，保留「非空的空值」；`VARCHAR2` / `CHAR` 目标仍按 Oracle 语义存 `NULL`。
- **大字段整块读入堆**：声明为大字段的列改为流式读取，小值内联，批次按 `batch-max-bytes` 提交，一次性流不进批次。
- **CDC 大事务缓冲占满堆**：超过阈值的值先落盘，提交、回滚、断流后删除；容量与清理保留期可配。
- **PostgreSQL / openGauss 源端流式无效**：读取期间保持只读事务，fetch size 真正生效；`enable-source-streaming=false` 可关闭。
- **厂商类型跨方言产出非法 DDL**：Oracle / SQL Server / DB2 / MySQL 专有类型先归一化再映射，未知类型跨族回退为可移植类型并记日志。
- **厂商类型取值未归一化**：`XMLTYPE`、`UNIQUEIDENTIFIER`、`MONEY`、`DATETIMEOFFSET`、`DECFLOAT`、`ENUM` / `SET` / `JSON` 等统一转换，全量、增量与三种 CDC 共用。
- **LogMiner 行外 LOB 静默跳过**：计入 `skippedMissingLob` 与按表计数并限流告警；`missing-lob-policy=fail` 可改为停住且不推进位点。
- **SQL Server 多列大字段流式断流**：读到大值列即落盘，不再跨列持有驱动流。
- **临时 LOB 与落盘文件泄漏**：成功、回滚、锁丢失与异常路径全部释放。
- **主键 / 游标 / 清洗列被误流式化**：这些列永远物化、清洗规则仍生效，超过上限明确报错。
- **模拟 upsert 与 CDC 重放重复绑定**：一次性流在第一次绑定前报错，可重放值正常执行。

## 安全加固

- 登录失败计数按用户名 64 字符截断、上限 10000 条，未认证请求无法再耗尽内存。
- `X-Forwarded-For` / `X-Real-IP` 只信任回环地址与 `app.security.trusted-proxies` 中显式列出的代理。
- `cdc-probe` 收紧为管理员专属。
- 补齐 Tomcat、Spring Framework、Spring Security 的安全修复版本。
- 默认口令账号必须改密后才能使用：登录后落在首页，改密弹窗自动打开且无法关闭，看板内容不下发，其他页面与 API 一律拒绝。
- 升级后首次启动自动生成随机密钥并重加密已存凭据，中途失败可在下次启动续做。
- 数据库连接与 AI 提供商改为专用表单对象，防止手工 POST 覆盖不可编辑字段。

## 新功能

- **同步对象面板支持目标表改名**：每行「源表 → 目标表」，留空即与源表同名，输入中的空格（含全角）自动去掉；窄屏改为上下排列。
- **大字段与流式设置卡片**：展示流式开关、内联与落盘阈值、单批预算、源端流式、落盘目录与容量、清理策略。
- **CDC 状态面板新增缺失 LOB 计数**：全局与按表计数，与「语句不被解析」分开。
- **全量并行支持所有有主键的键形态**：复合键、字符串 / UUID、小数与文本前导复合键均可切分；无窗口函数时自动回退顺序分块。
- **空表 INSERT-first 扩展到 MySQL / PostgreSQL**：首次全量写空表时用普通 `INSERT`，替代 upsert。
- **无主键表可按页续传**：目标为空且有可排序列时按页提交并写检查点，重启从目标行数续读。

## 界面

- 折叠为手机版时，语言 / 主题 / 赞赏 / 用户 / 菜单按钮组改为居右。
- 页脚统一为「© 2026 任冠华（沐沐开源工坊）· MIT 许可 · jync.qqmu.com」，官网地址可点击；名称与 logo 的品牌声明见 `TRADEMARK.md`。

## 限制（升级前务必阅读）

- **不是零拷贝**：Oracle `MERGE` 必须用服务端临时 LOB；模拟 upsert 与 CDC 重放要求值可重放；PG 逻辑解码与 SQL Server CDC 仍整行物化；MySQL 单值受 `max_allowed_packet` 限制。
- **需要落盘空间**：SQL Server 源的大值读到即落盘；PG 源请调低 `full-load-fetch-size`（1-10），堆至少容纳最大单值的 3 倍。
- **更新前镜像与删除镜像不落盘**：大字段表上密集的删除或移动主键的更新仍按「缓冲事件数 × 单行大小」占用堆内存。
- **LogMiner 行外 LOB 不可还原**：只能跳过或失败；补救方式是重建基线或该表改用轮询。
- **Oracle `VARCHAR2` / `CHAR` 的空串仍是 `NULL`**；厂商原语语义降级（`ENUM` / `SET` → `VARCHAR`、XML → `CLOB`、几何 → `WKB`、`SQL_VARIANT` 丢类型）。
- **无主键 OFFSET 续传按位置定位**：装载期间源库的并发插入/删除可能导致个别行漏读或重复；目标表已有数据时不启用。

## 兼容性说明

- **类型映射变化可能改变既有目标 DDL**：升级后首次结构同步前建议先做结构预检，或先对空库试跑。
- 新增开关均可关闭回退：`sync.lob.streaming-enabled`、`sync.lob.enable-source-streaming`、`sync.cdc.logminer.missing-lob-policy`。
- 落盘目录默认 `${java.io.tmpdir}/jync-lob`，需保证空间；容量受 `spill-max-bytes` 约束。
- **跨机反向代理部署必须配置 `app.security.trusted-proxies`**（或 `JYN_TRUSTED_PROXIES`）；同机回环代理无需配置。
- **升级后首次启动会重加密已存凭据**：生成的 `.key` 文件必须随 `./data` 一起备份，丢失后已存密码无法恢复。
- 配置项 `oracle-initial-load-insert-first` 更名为 `initial-load-insert-first`，旧键仍可用；该开关现在也作用于 MySQL 与 PostgreSQL 目标。

## 测试

- 2204 个测试：2203 通过，1 个按环境条件跳过；`*IT` 需 Docker 另跑。

## 升级

- 替换 `jync.jar` 重启即可。升级前备份 `./data` 与 `application.yml`。
- 容器部署将镜像 tag 更新为 `jync:1.7.8`（Docker Compose 与 `deploy/k8s/` 清单已同步）。
