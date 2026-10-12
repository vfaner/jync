# SyncTool v1.1.0

异构数据库实时同步工具。开箱即用的可执行 jar，放到内网机器上 `java -jar` 就能跑，不需要源码、不需要 Maven。

## 本次更新：登录与权限

v1.0.0 完全没有鉴权 —— 任何能访问到端口的人都可以调接口改数据库连接和 AI 配置。**建议所有 v1.0.0 用户升级。**

- 所有页面和接口都必须登录后才能访问，没有匿名入口
- 两个内置角色：`admin`（管理员，全部操作）和 `view`（访客，只读）
- 权限按 HTTP 方法判定：本工具所有写操作都是 POST，规则只有一条「POST 一律要求管理员」，新增 POST 接口不必改配置就自动受管。这条规则按方法生效，因而不覆盖 PUT / PATCH / DELETE —— 测试里有一条守卫盯着这件事，一旦有处理器映射到这些方法，构建就会失败
- 访客能看到所有列表页和详情页的数据，但界面上不出现任何写操作按钮，直接构造请求打接口也会被拒；新增与编辑表单页本身也不对访客开放
- 两个角色都能自助修改密码（当前密码 + 新密码至少 6 位）
- 启用 CSRF 防护：第三方页面无法借你已登录的浏览器发起写请求
- 登录密码用 BCrypt 单向哈希存储

## 快速开始

```bash
java -jar synctool.jar
```

访问 <http://localhost:8080>，用下面的账号登录。

## ⚠️ 首次启动必读

### 1. 立刻改掉初始密码

首次启动会在元数据库自动创建两个账号，**初始密码都是 `123456`**：

| 用户名 | 角色 | 权限 |
|---|---|---|
| `admin` | 管理员 | 全部操作 |
| `view` | 访客 | 只读 |

这个 jar 是公开可下载的，所以初始密码不是秘密。登录后点右上角用户名 → 修改密码。仍在用初始密码的账号，登录后每页顶部都会挂一条红色警告横幅，横幅只点名当前登录的这个账号。可以临时关掉，但浏览器一关又会回来，直到密码真的改了。

密码是 BCrypt 单向哈希，**忘记无法找回**。真忘了就删掉元数据库 `app_user` 表里对应那行，重启会重新种回初始密码。

### 2. 必须覆盖加密口令

jar 里内置了一个**公开的默认加密密钥**（`synctool-default-key-change-me`），它负责加密你存进去的每一个数据库密码和 AI API Key。用默认值等于没加密。

```bash
java -jar synctool.jar \
  --sync.crypto-password=你自己的强口令 \
  --sync.crypto-salt=你自己的16位十六进制盐
```

> **在第一次保存任何数据库连接之前就改好。** 已经存进去的密码是用旧口令加密的，事后换口令会导致这些密码无法解密，只能重新录入。

### 3. 数据目录

首次启动会在**当前工作目录**下创建 `./data`（H2 元数据库）、`./logs`、`./snapshots`。请固定在同一目录启动，或用绝对路径覆盖配置。

`./data/synctool.mv.db` 里存着你所有的数据库凭据和 AI API Key（加密的，但口令如果没改就等于明文）。**不要提交到版本库，备份和拷贝时按凭据文件对待。**

## 从 v1.0.0 升级

```bash
# 1. 停服并备份
sudo systemctl stop synctool
cp -r /opt/synctool/data /opt/synctool/data.bak

# 2. 换 jar
cp synctool.jar /opt/synctool/synctool.jar

# 3. 启动
sudo systemctl start synctool
```

元数据库用 `ddl-auto: update`，`app_user` 表会自动建好并种入上面两个账号。**加密口令请保持和之前一致**，否则已存的数据库密码无法解密。停机期间源库产生的变更会在重启后由游标机制自动补齐。

## 环境要求

- JDK 17 或以上
- 内置 MySQL 驱动；Oracle / SQL Server / DB2 等需要把驱动 jar 放到 `./drivers` 目录（见 README「加载非内置驱动」）

## 完整文档

[README（中文）](https://github.com/vfaner/synctool/blob/main/README.md) · [README (English)](https://github.com/vfaner/synctool/blob/main/README_EN.md)
