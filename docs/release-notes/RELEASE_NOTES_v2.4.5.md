# Jync v2.4.5 · 注释同步与游标优先级

## 本版概要

v2.4.5 聚焦**数据库元数据完整性与同步可控性**：SQL Server 源端可读取表 / 字段注释，Oracle、PostgreSQL、DB2、HANA、SQL Server 目标可写入注释；新增项目级游标列优先级与标签式批量设置；同时修复游标重载、无主键表与前端交互的边界问题。建议所有 2.4.x 用户升级。

## 新增

- SQL Server 源端读取 `MS_Description` 扩展属性，表注释与字段注释不再丢失。
- 目标端注释同步：Oracle / PostgreSQL 家族与 DB2 / HANA 使用 `COMMENT ON`，SQL Server 使用扩展属性；MySQL / OceanBase MySQL 模式保持内联 `COMMENT`。
- 项目级游标列优先级：按标签顺序逐表匹配，字段不存在或类型不可用时自动回退；单表手工游标仍然优先。各表进度页提供标签式批量设置。
- Kubernetes 清单默认引用官方多架构镜像，不再要求先本地构建。

## 优化

- SQL Server 结构同步按 schema 一次性预读注释，预读失败自动回退单表查询；旧目标表在首次同步时补一次历史注释。
- 保存游标优先级不扫描源库，只有实际换列的表才惰性重载；注释 DDL 只在新表创建或字段变更时执行。

## 修复

- 修复批量游标按钮无效、标签删除在触屏 / blur 下的竞态。
- 修复 Oracle / DB2 清空注释的非法 SQL，以及注释失败阻塞列变更的问题。
- 修复无主键表被优先级推进全量重装循环、优先级配置含 null 时保存 NPE。
- 修复内容校验使用旧水位误报，以及全量比对 / Redis / CDC 项目接受无效优先级的问题。
- 修复海量 Vastbase 目标整批写入失败：其解析器接受 `ON CONFLICT ... DO UPDATE`，但不接受 Jync 追加的“值未变化”`WHERE` 守卫；Vastbase 目标的原生批量写入保留 `ON CONFLICT` 并去掉该守卫；带安全余量的增量窗口改为 UPDATE + INSERT 模拟，未变化的重放仍报 0 行。
- 神通 OSCAR 目标的 upsert 改用 Oracle 兼容的 `MERGE`，不再生成其文档未覆盖的 `ON CONFLICT`。
- 修复源库与海量目标列名大小写不一致（如达梦大写、海量 `lower_case_column_names` 小写）时 upsert 报 `Column ... not found in upsert attrno state initialization`：写入前读取目标目录的实际列名并按它生成 SQL，实例内按表缓存，目标表变化后自动失效。
- 修复 MySQL → MySQL 结构同步改写同族类型的问题：`DATETIME` / `TIME` 保留源列的小数秒精度，`TEXT` / `BLOB` / `JSON` / `BINARY` 保留原类型与容量，`CURRENT_TIMESTAMP` 默认值同步匹配目标精度。
- 修复达梦 → MySQL 的 CDC 把有主键的小写表误判为“无主键表”：LogMiner 元数据先按事件中的实际大小写读取，失败再回退大写查找。

## 测试

6678 个测试：6677 通过，0 失败 0 错误，1 个仅限 Linux 的用例在 macOS 上按环境跳过。真实数据库集成测试（`*IT`）仍需 Docker 单独运行。

## 升级

替换 `jync.jar` 并重启即可，本版没有新增元数据表或字段。Docker 部署使用：

```bash
docker pull vfaner/jync:2.4.5
docker compose -f docker-compose.release.yml up -d
```

Kubernetes 修改 `deploy/k8s/kustomization.yaml` 的 `newTag` 为 `2.4.5` 后重新 `kubectl apply -k deploy/k8s/`。注释写入需要目标账号具备 `COMMENT ON` 或 SQL Server 扩展属性权限。
