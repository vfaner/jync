# Jync v1.11.0 · MongoDB 专属迁移

「专属迁移」新增 MongoDB → MongoDB 与 MongoDB → KingbaseES 两种整库一次性复制，与实时同步、ETL 完全隔离；ES / OpenSearch → Easysearch 的行为保持不变。

## 新增

- **MongoDB → MongoDB**：使用随包内置的原生 `mongodb-driver-sync`，自动发现集合并按模式筛选；复制集合选项（`capped`、`size`、`max`、`validator`、`collation` 等）与索引，文档原样批量写入并保留 `_id`。支持 `OVERWRITE` / `APPEND` / `FAIL_IF_EXISTS` 三种写入方式，capped 集合自动有序插入，重复 `_id` 在 `APPEND` 下跳过并 WARN；运行结束可做行数与抽样校验。
- **MongoDB → KingbaseES**：从有界样本推断 BSON → 列类型，自动建表并写入。`_id` 默认作为主键；`upsertOnId` 打开后使用 `INSERT … ON CONFLICT ("_id") DO UPDATE` 幂等重跑。嵌套文档与数组写成 TEXT 的 relaxed Extended JSON，不展开为列。DDL/DML 全部经方言引用标识符，值一律走参数绑定。
- **专属连接扩展**：连接模型新增 `databaseName`、`schemaName`、`extraParams` 三个可空字段，并引入按产品类型分派的连接适配器注册表；ES 逻辑逐行搬入搜索适配器，由既有测试锁定行为。

## 优化

- **同库守卫**：源与目标解析出相同的库名与 host 集合时拒绝运行，避免 `OVERWRITE` 在读源之前先删掉目标集合。
- **预检更完整**：采样读取异常兜底为 ERROR 而不让任务页崩溃；目标 schema 不存在、`APPEND` + `upsertOnId` 缺少 `_id` 唯一约束、字段名不能作为普通标识符，都在预检阶段给出明确错误。
- **写入正确性**：写关注错误不再被当作重复键吞掉；`APPEND` 列名按精确大小写比对；JDBC 元数据查询转义 `_` 与 `%`；BLOB 主键用 `setBytes` / `setNull(VARBINARY)` 绑定。
- **连接保护**：连接仍被迁移任务使用时禁止修改产品类型，避免已保存任务失效。
- **Kingbase URL 拼接**：正确处理 `extraParams` 的前导 `?` / `&`；`schemaName` 留空时才读取连接的当前 schema。

## 修复

- 两处可选集成测试自身的缺陷：`List.of` 不接受 null 元素导致含 SQL NULL 的行无法构造；upsert 后行数断言写错。真库测试现在可在 KingbaseES V9 的 PostgreSQL 与 Oracle 兼容模式下跑通。

## 提醒

- **凭据只存加密字段**，页面不回显；**MongoDB URI 与 Kingbase JDBC URL 中禁止写密码**（Kingbase 还禁止 `user` / `password` 参数），测试连接与运行日志不输出 URI。
- **MongoDB → MongoDB 的 `APPEND` 与 ES 语义不同**：保留目标已有文档，`_id` 冲突跳过并 WARN，不覆盖；需要以源为准时用 `OVERWRITE`，需要幂等覆盖请改用 KingbaseES 的 `upsertOnId`。
- 同一 MongoDB 集群跨库复制要建**两条连接记录**（同 host、不同 `databaseName`）；但源与目标指向同一个数据库时任务会拒绝运行。
- 专属迁移只复制运行那一刻的数据，没有断点续传；取消只在批间生效。嵌套文档/数组不展开；view 与 timeseries 集合不复制。
- **KingbaseES 已在 V9R1C1B25 的 PostgreSQL 与 Oracle 兼容模式下验证**；更老的 V8R3 缺少 `PG_CATALOG` 兼容视图，与内置 9.x 驱动不兼容，建议升级。
- 本次无破坏性变更，替换 `jync.jar` 重启即可；升级前备份 `./data` 与 `application.yml`。
