# Jync v1.7.7

新增华为 GaussDB、中兴 GoldenDB、腾讯 TDSQL 与阿里 PolarDB 预置类型；修复外部驱动 jar 的加载与检测；外部驱动预设可直接填写厂商 jar 里的驱动类。

## 新功能

- **华为 GaussDB 预设**：与 openGauss 是两个产品，使用 `com.huawei.gaussdb.jdbc.Driver` 与 `jdbc:gaussdb://`，默认端口 8000，方言归入 PostgreSQL 系，upsert 使用 `ON DUPLICATE KEY UPDATE`。驱动需自备 `gaussdbjdbc.jar`。
- **中兴 GoldenDB / 腾讯 TDSQL（MySQL 版）/ 阿里 PolarDB（MySQL 版）**：三者均以 MySQL 协议对外提供服务，预置类型复用内置 Connector/J，并沿用 MySQL 方言与元数据读取；GoldenDB 默认端口 8880，TDSQL 与 PolarDB 默认 3306，按控制台实际端口修改即可。
- **驱动类覆盖**：GBase、神通、GaussDB 等外部驱动类型可在「驱动 jar」卡片填写驱动类，留空则使用预设值。用于厂商 jar 的类名与预设不一致的场景，例如 GaussDB 的 `gsjdbc200.jar` 使用 `com.huawei.gauss200.jdbc.Driver`。
- 驱动 jar 路径现在也接受「存放 jar 的目录」，检测驱动类时会逐个扫描目录中的 jar。

## 修复

- **外部驱动建池失败**：HikariCP 通过线程上下文类加载器解析驱动类，而用户 jar 由独立的 `URLClassLoader` 加载。创建连接池期间临时切换上下文类加载器，修复 GaussDB 等外部驱动在「测试连接」时报 `Failed to load driver class ... in either of HikariConfig class loader or Thread context classloader` 的问题。
- **保存时不校验覆盖类**：判断是否需要外部 jar 时改为以实际生效的驱动类为准。之前覆盖类不在 classpath、但预设驱动已内置时，保存可以通过，连接时才报 `error.driver.absent`；现在保存阶段即拦截。
- **类型切换残留**：切换数据库类型时清空驱动类覆盖，避免上一个类型填写的类名静默覆盖新类型的预设；jar 卡片隐藏时输入框与按钮一并禁用，残留值不会随表单提交。

## 兼容性说明

- GaussDB 与 openGauss 的驱动、URL 前缀均不相同，请按实际产品选择预设，不要混用。
- TDSQL 与 PolarDB 的 PostgreSQL 版使用 PostgreSQL 预设；PolarDB 的 Oracle 兼容版是 PostgreSQL 协议产品，使用自定义类型并配上 `com.aliyun.polardb2.Driver`。
- `CUSTOM` 仍是未预置产品的兜底：结构同步走 ANSI 方言，数据写入使用先 UPDATE 后 INSERT 的模拟 upsert；目标表没有主键时重试可能产生重复行，建议为参与同步的表建立主键。

## 测试

- 1657 个测试：1656 通过，1 个按环境条件跳过；`*IT` 需 Docker 另跑。

## 升级

- 替换 `jync.jar` 重启即可，表结构随 `ddl-auto: update` 自动演进；升级前备份 `./data`。
- 已配置的外部驱动连接无需改动：驱动类留空即继续使用预设值。
- 容器部署将镜像 tag 更新为 `jync:1.7.7`（Docker Compose 与 `deploy/k8s/` 清单已同步）。
