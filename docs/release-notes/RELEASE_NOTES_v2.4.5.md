# Jync v2.4.5

## 本版概要

v2.4.5 聚焦**数据库元数据完整性与同步可控性**：SQL Server 源端现在能读取表 / 字段注释，Oracle、PostgreSQL、DB2、HANA、SQL Server 目标也能写入注释；新增项目级游标列优先级与标签式批量设置；同时修复游标列切换、无主键表、注释清理与前端交互中的边界问题。建议所有 2.4.x 用户升级。

## 新增

- SQL Server 源端读取 `MS_Description` 扩展属性，表注释与字段注释不再丢失。
- 目标端注释同步：Oracle / 达梦 / 崖山使用 `COMMENT ON`，PostgreSQL 家族使用 `COMMENT ON`，DB2 / HANA 使用 `COMMENT ON`，SQL Server 使用扩展属性；MySQL / OceanBase MySQL 模式保持内联 `COMMENT`。
- 项目级游标列优先级：按标签顺序逐表匹配，字段不存在或类型不可用时自动回退；单表手工游标仍然优先。
- 「各表同步进度」新增批量设置游标入口，支持标签式输入、拖动排序、键盘调整、去重与删除。
- Kubernetes 清单默认引用官方多架构镜像，不再要求先本地构建。

## 优化

- SQL Server 结构同步改为按 schema 一次性预读注释，预读失败自动回退到单表查询。
- 旧版本创建的目标表在首次同步时补一次历史注释，后续只通过注释变更事件维护。
- 保存项目级游标优先级不扫描源库、不逐表写配置；游标列真正变化的表才在后续同步中惰性重载。
- 注释 DDL 只在新表创建或字段变更时执行，避免每轮结构同步对所有表重复发 COMMENT。
- 标签删除使用 `pointerdown`，同时兼容鼠标与触屏，避免输入框 blur 重建标签导致删除失效。

## 修复

- 修复批量设置游标按钮动作 URL 取错元素、弹窗打开后输入和保存均无效的问题。
- 修复 Oracle / DB2 清空注释生成非法 SQL 的问题：Oracle 使用 `IS ''`，DB2 使用字符串常量 `''`。
- 修复列注释失败会阻塞列变更、导致整张表反复失败的问题；注释失败现在只记录 WARN。
- 修复无主键表被项目级游标优先级推进「目标非空不能全量重装」循环的问题；项目级优先级自动跳过无主键表。
- 修复优先级配置包含 null 元素时保存接口 NPE 的问题。
- 修复内容校验在游标列变化后拿旧水位比较、产生假差异的问题。
- 修复全量比对、Redis 目标与 CDC 项目接受无效游标优先级的问题；服务端与 UI 均做拒绝/隐藏。
- 修复标签删除在触屏或 blur 场景下的竞态。

## 测试

6653 个测试：6652 通过，0 失败 0 错误，1 个仅限 Linux 的用例在 macOS 上按环境跳过。真实数据库集成测试（`*IT`）仍需 Docker 单独运行。

## 升级

替换 `jync.jar` 并重启即可，本版没有新增元数据表或字段。Docker 部署使用新镜像 tag：

```bash
docker pull vfaner/jync:2.4.5
docker compose -f docker-compose.release.yml up -d
```

Kubernetes 修改 `deploy/k8s/kustomization.yaml` 的 `newTag` 为 `2.4.5` 后重新 `kubectl apply -k deploy/k8s/`。

注释写入需要目标账号具备相应权限：Oracle / PostgreSQL / DB2 / HANA 需要 `COMMENT ON` 权限，SQL Server 需要扩展属性权限。已有目标表的历史注释会在首次同步时补一次；之后注释变化按结构变更事件同步。
