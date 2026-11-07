# Jync v2.0.0

## 升级内容

- **Spring Boot 4.1.2**：Spring Framework 7.0、Spring Security 7.1、Hibernate ORM 7.4、Tomcat 11、Jackson 3、Lettuce 7、MongoDB 同步驱动 5.8；`javax.*` 全部迁移到 `jakarta.*`。功能范围与 v1.14.0 一致，仍要求 **Java 17+**，`application.yml`、数据目录、JDBC 驱动与反向代理配置都不用改。
- **对外格式不变**：
  - Jackson 3 恢复 1.x 的 epoch 时间戳输出，配置包 `meta.exportedAt` 仍为 ISO-8601；
  - Hibernate 7 的 `Instant` 固定映射为 `TIMESTAMP`，元数据库时间列与聚合查询保持 1.x 语义；
  - Spring Security 7 登录后保存的 URL 会附带 `?continue`，跳转与权限判定不变；
  - 存储密文格式不变：新加密器与旧 `Encryptors.delux` 双向互读，数据库口令、告警地址等已加密字段无需重新录入；
  - 内嵌 H2 固定为 **2.1.214**，1.x 写下的 `./data/jync.mv.db` 可原地升级（2.2+ 会报「Unsupported database file version」）；外部 MySQL / PostgreSQL 元数据库不受影响。
- **依赖瘦身**：移除运行期不会加载的构件（Hibernate 的 JAXB 绑定实现、Tomcat 的 Jakarta EL 实现、PostgreSQL 驱动的 `checker-qual`），Web 依赖改用 Boot 4 的 `spring-boot-starter-webmvc`。相较未瘦身的 Boot 4 构建 fat jar 减少约 1.76 MB；2.0.0 最终约 106.6 MB（v1.14.0 为 88.9 MB）。

## 修复与优化

- **Redis 缓存维护**：重建缓存与「删除前缀键」移到缓存投影卡片旁，只对 Redis 目标的管理员显示；重建全程持有同步租约，忙时返回可翻译提示，任何路径都会释放，不再与运行中的同步任务互踩，也不会误删相邻前缀的键。
- **CDC 与配置包**：CDC 重建在「位点已重置但消费者启动失败」时返回 503 并补写变更记录与审计，不再用裸 500 掩盖可重试的事实；重建相关错误全部改为 i18n key；配置包导入拒绝缺少 Redis 目标的 REDIS 项目，JDBC 项目缺目标库改为导入成功并警告，同时清掉跨类型的陈旧目标 id。
- **专属控件不再串到通用页面**：轮询专属的游标设置与说明只在管理员且非 CDC 时出现；重置进度在 CDC 项目隐藏，重建基线只对非 Redis 的 CDC 项目出现；Redis 项目不再渲染行数核对、目标预览、内容校验与第二套重建入口；ETL 的 MongoDB 续跑提示只对原生 MongoDB 源显示。
- **后端补齐与页面一致的守卫**：Redis 项目的存储策略仍是轮询时，启动与立即同步被拒；Redis 重建先校验目标类型再校验运行状态；被启用任务引用的 Redis 目标禁止编辑并提示任务名；保存 Redis 项目配置不再清空页面未渲染的选项、调度与性能字段。
- **配置正确性**：Oracle 挖掘账号与口令只对「Oracle 源库」保存，连接改用其他类型后自动清空，下次切回 Oracle 不会复用旧凭据；Redis key 前缀留空自动补 `jync:<id>:`，不再标必填；项目运行中锁定端点后由隐藏字段原样提交已保存拓扑。
- **性能**：Prometheus 抓取只查询项目/连接的 id 与名称，迁移首页只取计数，不再为两个标签字段加载带 `@Lob` 的整行实体。
- **平台适配**：修正 `.gitignore` 中 `*secret*` 误伤 `ConfigSecretTranslator.java` 的问题；`xdb` 与 `ojdbc8` / `orai18n` 统一到 23.26.3.0.0，消除 1.14 混用 21.5 驱动在 XMLTYPE 路径上的链接风险；清理 Boot 4 / Jackson 3 / Security 7 / Lettuce 7 的过期 API；设置页「运行时信息」新增 Spring Boot 与内嵌 Tomcat 版本。
- **测试基线**：全量 **5325** 个测试通过（1 个按环境跳过）；新增 H2 文件格式守卫、尾斜杠 404 回归、旧 `delux` 密文互读、跨模式控件可见性、投影查询与列表标题测试。
- **文档同步**：README / README_EN 技术栈更新为 Spring Boot 4.1；Dockerfile、Compose、K8s 清单与容器部署文档的示例版本号为 2.0.0。

## 已知边界

- 本版是平台升级，不改变 v1.14.0 的功能范围；1.14.0 的已知边界继续适用。
- 元数据库表结构由 Hibernate 自动更新。升级前请照常备份 `./data`、外部元数据库与 `application.yml`；使用外部 MySQL / PostgreSQL 元数据库时建议先在副本上启动一次。
- 内嵌 H2 固定为 2.1.214，不要自行换成 2.2 及以上；后续升级 H2 时会另行提供导出/导入迁移工具。
- `*IT` 集成测试（Oracle / MySQL / PostgreSQL / ClickHouse / MariaDB / Redis / MongoDB 真库）不在 `mvn test` 默认范围，需显式运行。

---

升级只需替换 `jync.jar` 并重启；连接、同步项目、ETL、专属迁移与 TLS 证书配置均不需要迁移。运行环境仍需 Java 17 或更高版本。
