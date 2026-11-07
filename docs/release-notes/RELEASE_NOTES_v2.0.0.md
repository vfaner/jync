# Jync v2.0.0

## 升级内容

- **Spring Boot 4.1.2 平台升级**：Spring Framework 7.0、Spring Security 7.1、Hibernate ORM 7.4、Tomcat 11、Jackson 3、Lettuce 7、MongoDB 同步驱动 5.8。Web、JPA、Security、Thymeleaf、Quartz 与全部数据库驱动的功能范围与 v1.14.0 一致。
- **Jakarta EE 命名空间**：`javax.*` 全部迁移到 `jakarta.*`；内嵌 Tomcat 从 9 升到 11（Servlet 6.1 / Jakarta EE 11）。仍要求 **Java 17+**，`application.yml`、数据目录、JDBC 驱动与外部反向代理配置不需要改。
- **兼容性处理**：升级不会改变页面与配置包对外的数据格式。
  - Jackson 3 默认把 `java.time` 写成 ISO 字符串；全局恢复 1.x 的 epoch 时间戳输出，页面 JavaScript 不需要改。配置包 `meta.exportedAt` 仍保持 ISO-8601 文本契约。
  - Hibernate 7 默认把 `Instant` 映射为 `TIMESTAMP WITH TIME ZONE`；显式固定为 `TIMESTAMP`，并让原生查询返回 JDBC 时间类型，元数据库时间列与聚合查询结果保持 1.x 语义。
  - Spring Security 7 登录后保存的 URL 会附带 `?continue` 标记；登录跳转与权限判定不变。
  - 内嵌 H2 固定为 **2.1.214**：1.x 在 `./data/jync.mv.db` 写下的 MVStore 文件使用旧写入格式，H2 2.2 及以上会直接报「Unsupported database file version」。固定 2.1.214 后，默认内嵌元数据库可以原地升级，无需导出导入；外部 MySQL / PostgreSQL 元数据库不受影响。
- **依赖瘦身**：移除不会被运行期加载的构件，保持全部功能不变。
  - Hibernate 的 JAXB 绑定实现（`jaxb-runtime`、`jaxb-core`、`txw2`、`istack-commons-runtime`、`angus-activation`）：应用只用注解映射，没有 `orm.xml` / `hbm.xml`。`jakarta.xml.bind-api` 保留——Hibernate 7 在启动构建元数据时会链接 `JAXBException`，即使没有 XML 映射文件。
  - Tomcat 的 Jakarta EL 实现（`tomcat-embed-el`）：模板使用 Thymeleaf SpringEL，未引入 Bean Validation。
  - PostgreSQL 驱动的 `checker-qual`（仅注解，运行期不加载）。
  - Web 依赖改用 Boot 4 的 `spring-boot-starter-webmvc`；WebSocket 与 AspectJ 织入继续保持排除。
  - 相对未瘦身的 Boot 4 构建，fat jar 减少约 1.76 MB（108.4 MB → 106.6 MB）；平台升级本身带来体积增长，2.0.0 最终约 106.6 MB（v1.14.0 为 88.9 MB）。

## 修复与优化

- **干净克隆可直接编译**：修正 `.gitignore` 中 `*secret*` 误伤 `ConfigSecretTranslator.java` 及其测试的问题，源码包与 CI 不再需要手工拷贝这两个文件。
- **Oracle XML 栈对齐**：`xdb` 改由 Spring Boot BOM 管理，与 `ojdbc8` / `orai18n` 统一到 23.26.3.0.0；`xmlparserv2_sans_jaxp_services` 同步到同一版本。1.14 的 21.5.0.0 `xdb` 与 23.x 驱动混用，在 XMLTYPE 路径上存在链接到驱动已移除内部类的风险；本版把两者对齐消除该风险。
- **过期 API 清理**：`EnvironmentPostProcessor` 迁移到 Boot 4 的 `org.springframework.boot.EnvironmentPostProcessor` 注册键；Jackson 3 的 `asText` / `isTextual` 全部替换为 `asString` / `isString`；`HttpStatus.PAYLOAD_TOO_LARGE` 改为 `CONTENT_TOO_LARGE`；`org.springframework.lang.Nullable` 改为 jspecify；Lettuce 集群节点标志 `MASTER` 改为 `UPSTREAM`；MySQL 驱动改用 `com.mysql:mysql-connector-j` 坐标。存储密文格式不变：新增的兼容加密器与旧 `Encryptors.delux` 双向互读。
- **运行时信息更完整**：设置页「运行时信息」卡片新增 Spring Boot 与内嵌 Tomcat 版本，升级后不必查日志或 jar 文件名就能确认实际运行的平台。
- **Redis 缓存维护归位**：重建缓存与「先删除前缀键」从日常运行按钮行移到右侧缓存列，紧跟「缓存投影」卡片；只对目标为 Redis 的项目、且仅管理员渲染，数据库项目与只读账号的页面不再出现这组 Redis 专属操作。
- **测试基线**：全量 5286 个测试通过（1 个按环境跳过）；Boot 4 拆分的测试切片补齐 `webmvc-test`、`data-jpa-test` 与 `security-test` 依赖，并新增内嵌 H2 文件格式守卫、尾斜杠 404 回归、旧 `delux` 密文互读与 Redis 缓存维护卡片可见性测试。
- **文档同步**：README / README_EN 技术栈更新为 Spring Boot 4.1；Dockerfile、Compose、K8s 清单与容器部署文档的示例版本号更新为 2.0.0。

## 已知边界

- 本版是平台升级，不改变 v1.14.0 的功能范围；1.14.0 的已知边界继续适用。
- 元数据库表结构由 Hibernate 自动更新。升级前请照常备份 `./data`、外部元数据库与 `application.yml`；使用外部 MySQL / PostgreSQL 元数据库时建议先在副本上启动一次。
- 内嵌 H2 固定为 2.1.214，以保持 1.x 的 `data/jync.mv.db` 可直接打开；不要自行把 H2 换成 2.2 及以上版本。后续升级 H2 时会另行提供导出/导入迁移工具。
- `*IT` 集成测试（Oracle / MySQL / PostgreSQL / ClickHouse / MariaDB / Redis / MongoDB 真库）不在 `mvn test` 默认范围，需显式运行。

---

升级只需替换 `jync.jar` 并重启；连接、同步项目、ETL、专属迁移与 TLS 证书配置均不需要迁移。运行环境仍需 Java 17 或更高版本。
