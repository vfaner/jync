# 真实数据库验证矩阵

这份文档说明每个数据库产品在 Jync v1.12.0 上**实际验证到什么程度**：用什么版本/镜像、走哪条驱动、验证了哪些策略、验证级别是「自动化容器」「环境门控」还是「手工」，以及最近一次验证是什么时候。

> 验证级别只描述**测试是否存在并能跑**，不承诺所有组合。未跑过的产品在本表中标为「未验证」，不会因为驱动已内置或单元测试通过就写成已验证。

## 验证级别

| 级别 | 含义 | 运行方式 |
|---|---|---|
| 自动化容器 | 测试自己拉起容器，Docker 可用即执行；无 Docker 时通过 `@Testcontainers(disabledWithoutDocker = true)` 干净跳过 | `mvn -q test -Dtest=<类名> -Dapi.version=1.44` |
| 环境门控 | 需要外部实例，通过 `JYNC_*` 环境变量开启；变量缺失时整类跳过 | 见下文的变量表 |
| 手工 | 没有公开容器或驱动不可再分发，需要使用者按步骤自备实例/驱动；不进入自动脚本 | 见「手工验证步骤」 |
| 仅元数据 | 只作为 Jync 自身的元数据库使用，不作为同步源/目标 | 测试内嵌 H2 |

`-Dapi.version=1.44` 用于较新 Docker Engine 上固定 Testcontainers 的 API 协商版本；Docker 版本较老时可去掉。

## 矩阵

| 产品 | 角色 | 测试版本 / 镜像 | 驱动 | 已验证策略 | 验证级别 | 最后验证 | 备注 |
|---|---|---|---|---|---|---|---|
| PostgreSQL | 源 + 目标 | `postgres:16`、`postgres:16-alpine` | `org.postgresql:postgresql`（Boot 管理） | 全量、时间戳/主键轮询、upsert、行数审计、内容校验、PG 逻辑 CDC、元数据库 | 自动化容器 | v1.11.0 及以前持续回归 | 目标端类型/主键/LOB 行为由多组 IT 覆盖 |
| MySQL | 源 + 目标 | `mysql:8.0` | `mysql:mysql-connector-java:8.0.33` | 全量、轮询、upsert、binlog CDC、元数据库 | 自动化容器 | v1.11.0 及以前持续回归 | binlog CDC 有独立容器套件 |
| MariaDB | 源 + 目标 | `mariadb:11.4` | `org.mariadb.jdbc:mariadb-java-client`（Boot 管理） | 全量、时间戳轮询、更新送达、空转不写入、内容校验 | 自动化容器 | **2026-11-03（v1.12.0）** | `MariaDbPollingIT`；驱动用普通正数 fetch size 流式读取，URL 带 `useAffectedRows=true` |
| Oracle | 源 + 目标 | `gvenzl/oracle-free:23`；`gvenzl/oracle-xe:11-slim`（目标标识列场景） | `com.oracle.database.jdbc:ojdbc8` | 全量、轮询、upsert、LogMiner CDC、LOB、元数据库、11g 目标 DDL 兼容 | 自动化容器 | v1.11.0 及以前持续回归 | 23 Free 在 arm64 原生运行；11 XE 用 amd64 模拟 |
| SQL Server | 源 + 目标 | `mcr.microsoft.com/mssql/server:2022-latest` | `com.microsoft.sqlserver:mssql-jdbc` | 全量、CDC 源、目标写入（含 LOB） | 自动化容器 | v1.11.0 及以前持续回归 | |
| openGauss | 源 + 目标 | `opengauss/opengauss:5.0.0` | `org.opengauss:opengauss-jdbc:6.0.0-og` | 全量、轮询、upsert、PG 逻辑 CDC、Oracle/MySQL/SQL Server 写入适配 | 自动化容器 | v1.11.0 及以前持续回归 | 国产 PG 系内核的代表；其余内核按同一方言族覆盖 |
| ClickHouse | 仅源 | `clickhouse/clickhouse-server:24.8` | `com.clickhouse:clickhouse-jdbc:0.11.0`（**仅测试**，应用不内置） | 元数据读取、无主键 OFFSET 分块全量（2500 行 / 块大小 1000）、空转、目标建表类型兼容 | 自动化容器 | **2026-11-03（v1.12.0）** | `ClickHouseSourceIT`；目标为 PostgreSQL 16；驱动未随包分发 |
| Doris | 仅源 | 由使用者提供（无自动镜像） | MySQL Connector/J | 无主键 duplicate-key 表全量、目标建表、值到达 | 环境门控 | 本次未运行 | `DorisSourceIT`；需要 `JYNC_DORIS_IT_URL/USER/PASSWORD` |
| KingbaseES | 源 + 目标 | 由使用者提供（无公开镜像） | `cn.com.kingbase:kingbase8:9.0.1.jre7`（内置） | 轮询建表与行送达、空转不重复；专属迁移建表/upsert | 环境门控 | 历史真库 V9R1C1B25（专属迁移）；轮询 IT 本次未运行 | `KingbasePollingIT` + `KingbaseLiveIT`；V8R3 与内置 9.x 驱动不兼容 |
| YashanDB | 源 + 目标 | 由使用者提供（无公开镜像） | `com.yashandb:yashandb-jdbc:1.9.27`（内置） | 轮询建表与行送达；YStream CDC 需单独探测 | 环境门控 | 本次未运行 | `YashanDbPollingIT`；本测试**不宣称** YStream CDC 已验证 |
| Elasticsearch SQL | 仅源 | 由使用者提供（集群 + X-Pack SQL JDBC jar） | 用户自备 `customJarPath`（如 `org.elasticsearch.xpack.sql.jdbc.EsDriver`） | 简单索引全量到 JDBC 目标 | 手工 / 环境门控 | 本次未运行 | `ElasticsearchSqlSourceIT`；驱动不在 Maven Central，缺 jar 时 `assumeTrue` 跳过 |
| H2 | 仅元数据 | 内嵌 | `com.h2database:h2` | Jync 元数据库启动/迁移 | 自动化（单元/上下文测试） | v1.11.0 及以前持续回归 | 不作为同步源或目标 |
| MongoDB（原生驱动） | 仅 ETL/专属迁移源 | `mongo:6` | `org.mongodb:mongodb-driver-sync` | ETL 取数、Mongo → Mongo、Mongo → KingbaseES | 自动化容器 | v1.11.0 | 不参与 JDBC 实时同步矩阵 |

## 环境变量（门控测试）

| 测试类 | 必需变量 | 可选变量 | 说明 |
|---|---|---|---|
| `KingbasePollingIT` | `JYNC_KINGBASE_IT_URL`、`JYNC_KINGBASE_IT_USER`、`JYNC_KINGBASE_IT_PASSWORD` | `JYNC_KINGBASE_IT_SOURCE_SCHEMA`（默认 `jync_it_src`）、`JYNC_KINGBASE_IT_TARGET_SCHEMA`（默认 `jync_it_tgt`） | 同一实例两个 schema 作源/目标；测试自行建/删 schema 与表 |
| `YashanDbPollingIT` | `JYNC_YASHAN_IT_URL`、`JYNC_YASHAN_IT_USER`、`JYNC_YASHAN_IT_PASSWORD`、`JYNC_YASHAN_IT_SCHEMA`、`JYNC_YASHAN_IT_TARGET_SCHEMA` | `JYNC_YASHAN_IT_TARGET_URL/USER/PASSWORD` | 两个 schema 必须已存在；测试只建/删自己的表 |
| `DorisSourceIT` | `JYNC_DORIS_IT_URL`、`JYNC_DORIS_IT_USER`、`JYNC_DORIS_IT_PASSWORD` | — | URL 指向的库必须已存在；目标用 PostgreSQL 容器 |
| `ElasticsearchSqlSourceIT` | `JYNC_ES_IT_URL`、`JYNC_ES_IT_DRIVER_JAR` | `JYNC_ES_IT_USER/PASSWORD`、`JYNC_ES_IT_DRIVER_CLASS`（默认 `org.elasticsearch.xpack.sql.jdbc.EsDriver`）、`JYNC_ES_IT_INDEX`（默认 `jync_it_sync`） | 索引通过 REST 写入（ES SQL 只读），目标用 PostgreSQL 容器 |

```bash
# 示例：Kingbase
JYNC_KINGBASE_IT_URL=jdbc:kingbase8://127.0.0.1:54321/test \
JYNC_KINGBASE_IT_USER=system JYNC_KINGBASE_IT_PASSWORD=... \
mvn -q test -Dtest=KingbasePollingIT -Dapi.version=1.44

# 示例：Elasticsearch SQL（需要 X-Pack SQL JDBC jar）
JYNC_ES_IT_URL=jdbc:es://http://127.0.0.1:9200 \
JYNC_ES_IT_DRIVER_JAR=/opt/drivers/elasticsearch-x-pack-sql-jdbc.jar \
mvn -q test -Dtest=ElasticsearchSqlSourceIT -Dapi.version=1.44
```

变量缺失时，JUnit 的 `@EnabledIfEnvironmentVariable` / `assumeTrue` 会让测试显示为 `Skipped: 1`，不是失败。

## 手工验证步骤

### Apache Doris（仅源）

1. 准备一个可写的 Doris 库（FE 9030），确认 `SELECT DATABASE()` 返回目标库。
2. 设置 `JYNC_DORIS_IT_URL=jdbc:mysql://<host>:9030/<db>`、`JYNC_DORIS_IT_USER`、`JYNC_DORIS_IT_PASSWORD`。
3. 运行 `mvn -q test -Dtest=DorisSourceIT -Dapi.version=1.44`。测试会建一张 `DUPLICATE KEY` 表、跑一轮全量并核对 PostgreSQL 目标行值，最后删表。
4. 测试通过只代表「该 Doris 版本 + 该驱动 + 该表形态」的轮询全量可用；Doris 不做目标，也没有 CDC。

### Elasticsearch SQL（仅源）

1. 从 Elastic 官方渠道取得与集群版本匹配的 X-Pack SQL JDBC jar（Maven Central 不提供）。
2. 设置 `JYNC_ES_IT_URL=jdbc:es://http://<host>:9200`、`JYNC_ES_IT_DRIVER_JAR=<jar 路径>`，需要认证时再加 `JYNC_ES_IT_USER/PASSWORD`。
3. 运行 `mvn -q test -Dtest=ElasticsearchSqlSourceIT -Dapi.version=1.44`。测试通过 REST 写入两个文档，跑一轮全量并核对目标表，最后删除索引。
4. 受 `index.max_result_window` 约束；测试只覆盖简单索引到 JDBC 目标，不代表复杂的 SQL 方言面。

## 运行脚本

```bash
# 默认：MariaDB + ClickHouse 两个容器套件
bash scripts/run-verification-matrix.sh

# 额外跑历史容器套件（Oracle / MySQL / openGauss / SQL Server）
JYNC_FULL_MATRIX=1 bash scripts/run-verification-matrix.sh
```

脚本会逐个打印 `RUN` / `SKIP` 与原因；门控测试没有环境变量时不会失败。脚本不引入 CI，也不在默认 `mvn test` 中执行 `*IT`。
