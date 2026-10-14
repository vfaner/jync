# Jync v1.7.1

上一个公开发布是 **v1.5.0**（v1.6.0 / v1.7.0 只打了 tag 未发 release），本版把 v1.5.0 之后的全部变化一次带齐。

## 相比 v1.5.0 新增

### 日志 CDC 从 1 种扩到 4 种（v1.6.0 / v1.7.0）

- **Oracle LogMiner**（v1.6.0）：挖掘 redo 日志流式回放，七项就绪探测，SCN 位点
- **PostgreSQL 逻辑解码 + SQL Server CDC**（v1.7.0）：各五项就绪探测，LSN 位点
- 四种引擎（MySQL binlog / Oracle LogMiner / PG logical slot / SQL Server CDC）均可按项目从轮询切换：秒级延迟，INSERT / UPDATE / DELETE 全捕获，不建触发器、只读解析
- 共用一套机制：keyset 分块基线、at-least-once（先数据后位点）、多实例租约互斥（接管 ≤ 60s）、ERROR 帽等人处置、一键重建基线、CDC 状态面板

### v1.7.1 CDC 可靠性加固

- Docker 真库矩阵实测 12 条故障路径（binlog purge、断线、源库重启、位点失效等）并修复：binlog XA 事务与心跳事件、LogMiner UPDATE 旧镜像与水位推进、PG slot 调用签名与 test_decoding 类型转换、SQL Server 22054 自愈 / purge 停放 / 罢工计数、跨策略切换 autoCommit 恢复
- 位点纪律收口：数据提交成功后才推进位点；租约丢失不推进

### 前端 overhaul

- 统一居中弹窗 JyncDialog：layer 风圆脸 msg 黑条（非阻塞）+ 白卡（阻塞），flash 横幅改走弹窗
- 未保存配置三选一拦截（保存并执行 / 不保存执行 / 取消）；CDC 相关按钮随策略卡实时联动
- 状态徽章三处口径统一（ERROR 帽优先，徽章永远等于按钮）；CDC stop 后状态正确回落
- 项目列表瘦身、前端审计五处修复（LOG_CDC 口径与后端对齐、程序化勾选补脏标记、死按钮清除、msg 可访问性）

### 文档

- README 精简重写（1024 → 301 行），截图改宫格排版，图片统一迁入 `docs/images/`

## 升级

替换 jar 重启即可，表结构随 `ddl-auto: update` 自动演进；**升级前备份 `./data`**。停机期间源库变更重启后自动补齐。
