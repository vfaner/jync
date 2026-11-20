# Jync v2.4.4

## 修复

- **CDC 换源端点 / 重跑基线不再复活旧水位**：位点写入有一秒节流缓冲，停止时的最后一次 flush 可能与锚点重置竞态——重置先把位点行清空，拿着旧缓冲的 flush 随后把旧源的 file:pos 写回，下次启动对着新源从旧水位续传，静默跳过整段数据。现在重置会等正在进行的那次位点写入结束，并把缓冲标记为已丢弃；被丢弃的缓冲绝不再写。
- **表选择索引的并发读取不再有空指针风险**：同步配置的大小写无关索引在 CDC 每事件路径上被多线程共享，旧实现用三个非 volatile 字段分别发布，读者可能看到「守卫已更新、索引还是 null」而跳过重建直接 NPE。现在守卫与索引作为一个 volatile 快照整体发布，读者永远拿到一致的一份。
- **DB2 / SQL Server 的可续跑 ETL 不再被冗余 ORDER BY 卡死**：`FOR FETCH ONLY`、`OPTIMIZE FOR n ROWS`（DB2）与 `AS limit` / `AS offset` 这类未加引号的别名（SQL Server）被误判为限制行数的子句，ORDER BY 因此被保留，而派生表里没有 FETCH FIRST / TOP 时数据库拒绝 ORDER BY——任务每次运行和预检都失败。现在只有真正的限流子句（TOP、FETCH FIRST/NEXT、带计数的 LIMIT/OFFSET、FOR UPDATE / XML / JSON）保留排序，只读与优化子句的冗余排序照常去除。
- **启动失败页不再被损坏的 H2 文件头噎死**：文件头里的写格式数字若是一段 int 装不下的长数字串，分析器会直接抛出 NumberFormatException，被 Spring 吞成通用错误——恰恰是这个页面要救的场景。现在报告「写格式数字无法解析，可能已损坏」，备份与恢复步骤照常给出。
- **Webhook 地址校验检查每一个解析地址**：主机名可能返回多条 A 记录，旧实现只查第一条，公网答案可以为同名单里的内网 / 元数据地址「作保」，而实际请求可能连到后者。现在所有解析地址都必须通过地址策略。校验与请求之间翻转答案的 DNS 重绑定仍是残余风险，内网 webhook 请用「允许私网地址」开关显式放行。

## 清理

- 删除生产零调用的 `AiEndpointValidator.isPermitted()`（请求时校验一律走 `validateBaseUrl`）。
- 去重七组逐字相同的工具方法：三个迁移选项类的 JSON 字段读取合并为 `OptionsJson`；审计与 DDL 任务的「当前操作者」合并为 `CurrentActor`；三个 AI 助手控制工具的动作解析合并为 `SupportArgs.action`；连接测试与健康探活的驱动错误文案合并为 `JdbcUtil.sqlMessage`；两个设置开关的显式值解析合并为 `SystemSettingService.parseExplicit`；数据预览的配置解析委托 `SyncContextFactory.parseConfig`。行为与文案不变。
- 容器镜像标签与运行版本对齐（Dockerfile / compose / K8s 清单此前停留在 2.4.2）；修正 Dashboard 进程指标端点过时的「仅管理员」注释（2.4.3 起已对所有登录角色开放）。

## 测试

6461 个测试：6460 通过，0 失败 0 错误；1 个仅限 Linux 的用例在 macOS 上按环境跳过。新增 14 个回归测试，覆盖位点重置竞态、选择集并发发布、DB2 / SQL Server 子句判定、损坏文件头与多地址 webhook 校验。`*IT` 需 Docker 另跑。

## 升级

替换 `jync.jar` 并重启即可，从 2.4.3 升级没有元数据库结构变更。运行环境仍需 Java 17 或更高版本。
