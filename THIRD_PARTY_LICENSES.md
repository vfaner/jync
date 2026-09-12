# 第三方组件许可

SyncTool 自身以 MIT 许可证发布（见 `pom.xml` 中的声明）。发行包（可执行 jar）内除本项目代码外，还内置了下列第三方 JDBC 驱动。这些驱动均**未经修改**，全部来自 Maven Central，各自适用其原厂许可证；jar 内自带的许可与版权声明文件原样保留在可执行包的 `BOOT-INF/lib/` 中。本文件只是摘要，一切以各许可证原文为准。

## 内置 JDBC 驱动

| 数据库 | Maven 坐标：版本 | 许可证 | 驱动 jar 内的许可文件 |
|---|---|---|---|
| MySQL | `mysql:mysql-connector-java:8.0.33`（实际构件为 `com.mysql:mysql-connector-j`） | GPLv2 + Universal FOSS Exception 1.0 | `LICENSE` |
| PostgreSQL | `org.postgresql:postgresql:42.3.8` | BSD-2-Clause | `META-INF/LICENSE` |
| MariaDB | `org.mariadb.jdbc:mariadb-java-client:3.1.4` | LGPL-2.1 | — |
| Microsoft SQL Server | `com.microsoft.sqlserver:mssql-jdbc:10.2.3.jre8` | MIT | — |
| Oracle | `com.oracle.database.jdbc:ojdbc8:21.5.0.0` | Oracle Free Use Terms and Conditions (FUTC) | `META-INF/license.txt` |
| IBM DB2 | `com.ibm.db2:jcc:11.5.9.0` | IBM International Program License Agreement (IPLA) | — |
| openGauss | `org.opengauss:opengauss-jdbc:6.0.0-og` | BSD-2-Clause | `META-INF/LICENSE` |
| 达梦 DM | `com.dameng:DmJdbcDriver18:8.1.3.140` | Apache License 2.0 | — |
| 人大金仓 KingbaseES | `cn.com.kingbase:kingbase8:9.0.1.jre7` | Apache License 2.0 | — |
| H2（本工具的元数据库） | `com.h2database:h2:2.1.214` | MPL 2.0 / EPL 1.0（双许可） | — |

其中 PostgreSQL、MariaDB、SQL Server、ojdbc8、DB2 jcc 的版本由 Spring Boot 2.7.18 的依赖管理统一锁定。

各许可证原文：

- GPLv2：<https://www.gnu.org/licenses/old-licenses/gpl-2.0.html>；Universal FOSS Exception 1.0：<http://oss.oracle.com/licenses/universal-foss-exception>。该例外明确允许在满足条件时将 Connector/J 与适用 FOSS 例外的独立开源程序组合分发
- BSD-2-Clause：<https://opensource.org/licenses/BSD-2-Clause>
- LGPL-2.1：<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
- MIT：<https://opensource.org/licenses/MIT>
- Apache License 2.0：<https://www.apache.org/licenses/LICENSE-2.0>
- MPL 2.0：<https://www.mozilla.org/MPL/2.0/>；EPL 1.0：<https://opensource.org/licenses/eclipse-1.0.php>

### Oracle ojdbc8（FUTC）的再分发说明

ojdbc8 按 Oracle Free Use Terms and Conditions 发布，其中明确允许再分发未修改的程序与文档（原文）：

> "(b) redistribute unmodified Programs and Programs Documentation, under the terms of this License, provided that You do not charge Your end users any additional fees for the use of the Programs."

条件包括：再分发时必须附带该许可证副本；不得移除 Oracle 或许可方的专有权标识与声明。SyncTool 为免费开源软件，不就驱动程序另行收费，jar 未经修改原样打包，`META-INF/license.txt` 保留，并在此附上许可证全文链接：<https://www.oracle.com/downloads/licenses/oracle-free-license.html>。

### IBM DB2 jcc（IPLA）的说明

`com.ibm.db2:jcc` 由 IBM 发布到 Maven Central，POM 中声明适用 International Program License Agreement（IPLA）：<https://www.ibm.com/support/customer/csol/terms/?ref=L-AJVM-KLN94L-01-11-2023-zz-en>。本项目仅将该未经修改的构件随包提供，相关商标与版权归 IBM 所有。

## 未内置的驱动

以下两类数据库的 JDBC 驱动**不随本项目分发**，需要在连接配置中填写服务器本地的驱动 jar 路径，运行时动态加载：

- **GBase（南大通用）**：`com.gbase.jdbc.Driver`
- **神通 Oscar**：`com.oscar.Driver`
- **自定义（CUSTOM）**类型：任意第三方驱动

原因是这两个厂商未在 Maven Central 发布官方构件，本项目也未获得其驱动的再分发授权。请从数据库厂商处获取驱动 jar。

## 其他第三方依赖

除 JDBC 驱动外，发行包还包含 Spring Framework / Spring Boot、Hibernate、HikariCP、Quartz、Thymeleaf 等开源依赖，各自适用其构件中声明的许可证。完整的依赖清单可用以下命令生成：

```bash
mvn dependency:tree
```
