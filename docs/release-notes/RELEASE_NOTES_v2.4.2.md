# Jync v2.4.2

## 修复

- **元数据库枚举列升级**：修复 2.3.0 及更早版本升级到 2.4.x 后替换 `jync.jar` 无法启动的问题。Hibernate 7 把 `@Enumerated(STRING)` 映射成建表时那一版常量集冻结下来的类型——H2 / MySQL 是原生 `ENUM`，PostgreSQL / Oracle 是 `VARCHAR` + 检查约束——而 `ddl-auto=update` 只比对类型名，从不扩充取值。2.4.0 新增角色 `OPS`、2.4.1 新增 `TDENGINE` 连接类型等常量后，旧库拒绝写入（H2 报 22030， MySQL 截断，PostgreSQL / Oracle 检查约束违反）；本地管理员账号播种在启动事件里执行，异常逃出监听器，进程在「Started JyncApplication」之后退出。现在全部 24 个枚举列按文本映射，首次启动由 Hibernate 自动把旧的原生 `ENUM` 改为 `VARCHAR`；Hibernate 建表时写下的取值检查约束（H2 `CONSTRAINT_N`、MySQL `<表名>_chk_N`、PostgreSQL `<表>_<列>_check`、Oracle `SYS_Cxxxxx`）由启动迁移自动删除，按约束定义识别，`NOT NULL` 不受影响。新建的库同样会在首次启动时清掉这些约束，否则下一个版本新增的枚举值仍会被拒绝。日志会逐条列出转换与删除，无需手工执行 DDL；升级后回退到 2.4.1 仍可启动。
- **升级失败提示**：元数据库被占用、升级后启动失败等启动诊断改为中文说明；一键更新回滚时等待旧进程退出（最长 15 秒）再恢复文件，避免 Windows 上文件占用导致回滚失败。

## 升级

替换 `jync.jar` 并重启即可。首次启动会自动完成上述列类型转换与旧约束清理，数据无损；H2、MySQL 8、PostgreSQL 16、Oracle 23 的旧版元数据库均验证通过，二次启动不再产生任何结构变更。运行环境仍需 Java 17 或更高版本。
