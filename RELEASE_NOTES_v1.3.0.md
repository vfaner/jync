# Jync v1.3.0

**SyncTool 正式更名为 Jync（捷同）**，读作 "sync" —— J 代表 Java，捷同取「敏捷同步、两库皆同」之意。极简 4 字母生造词，全网无重名，从此好搜好记。功能与 v1.2.1 完全一致，本版只做更名与随之而来的兼容性处理。

## 本次更新

### 更名

- 项目名 SyncTool → **Jync 捷同**，仓库迁至 [github.com/vfaner/jync](https://github.com/vfaner/jync)（旧地址自动跳转）与 [gitee.com/super_rgh/jync](https://gitee.com/super_rgh/jync)
- 可执行 jar 更名为 `jync.jar`，日志文件更名为 `logs/jync.log`
- Java 包名 `com.synctool` → `com.qqmu.jync`，Maven 坐标 `com.qqmu.jync:jync`
- 界面品牌位（导航栏、登录页、浏览器标题）显示 **Jync 捷同**

### 升级兼容（自动完成，无需手工操作）

- **元数据库自动迁移**：启动时按生效的 `spring.datasource.url` 判断——若目标库名不再是 `synctool`（如默认的 `data/jync`），且同目录下存在旧文件 `synctool.mv.db`，会自动改名到新路径，项目、连接、变更日志全部保留；启动输出出现「已迁移元数据库」即完成
- **外部覆盖配置不动则数据不动**：配置项前缀仍为 `sync.*`，外部 `application.yml` 无需改动。若你的覆盖文件把 `spring.datasource.url` 指向老路径（如 `/opt/synctool/data/synctool`），程序原样打开原文件，不做任何迁移。**若想把 URL 改到新路径，必须同时把旧 `synctool.mv.db` 移动并改名到新路径**——只改 URL 不搬文件，程序会在新路径建一个空库
- **换了安装目录要带上数据**：迁移只认「URL 指向的目录」。像新版 README 那样把安装目录从 `/opt/synctool` 换成 `/opt/jync` 的，请把整个 `data/` 目录（连同 `snapshots/`、`logs/`）一起搬到新工作目录，否则程序在新目录找不到旧库，会以空库启动（旧数据仍在原目录，搬回来即可恢复）
- **已存数据库密码不受影响**：加密默认密钥保持原值不变，升级后无需重新录入任何连接
- **浏览器偏好自动迁移**：主题、语言、时区、日志页自动刷新开关的旧 localStorage / cookie 键会在首次打开页面时搬到新键名下，深色模式与语言选择不丢；旧 cookie 搬完即作废
- **日志文件更名**：`logs/synctool.log` → `logs/jync.log`。有 logrotate / 日志采集 / tail 监控盯着旧路径的，记得改配置；旧文件不会再增长
- **AI 语法检查临时对象前缀更名**：`SYNCTOOL_AI_CHECK_*` → `JYNC_AI_CHECK_*`。旧版本若在强杀后残留过临时对象，清理时按旧前缀找
- **1.2.x 的自动更新检查收不到本版通知**：仓库改名后 GitHub 对旧地址返回 301，而 1.2.x 的版本检查不跟随重定向。已部署的 1.2.x 实例需要手动升级一次；1.3.0 起检查已改为跟随重定向，之后不再受改名影响

## 升级

换 jar、重启即可。老的部署脚本只需改两处：jar 文件名（`synctool.jar` → `jync.jar`）和 systemd 单元名（如沿用了 README 示例的 `synctool.service`，建议同步更名，不改也能运行）。

**加密口令必须与之前一致**，否则已存的数据库密码无法解密。停机期间源库产生的变更，重启后由游标机制自动补齐。

**回滚到 1.2.x**：把 `data/jync.mv.db` 改名回 `data/synctool.mv.db` 即可，数据本身双向兼容。浏览器端的主题/语言偏好升级时已迁到新键名并清掉旧键，回滚后可能需要重选一次。

从旧版本升级请先读 [v1.2.0 发布说明](https://github.com/vfaner/jync/releases/tag/v1.2.0)。

## 首次启动必读

1. **改掉初始密码**：内置 `admin` / `view` 两个账号，初始密码都是 `123456`，登录后点右上角用户名修改
2. **覆盖加密口令**：jar 内置公开默认密钥，首次保存连接前请通过 `--sync.crypto-password` 和 `--sync.crypto-salt` 设置自己的口令
3. **固定数据目录**：程序在启动目录下创建 `./data`（元数据库，含加密凭据）、`./logs`、`./snapshots`，请固定在同一目录启动

## 完整文档

[README（中文）](https://github.com/vfaner/jync/blob/main/README.md) · [README (English)](https://github.com/vfaner/jync/blob/main/README_EN.md)
