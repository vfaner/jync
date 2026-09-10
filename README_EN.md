# SyncTool · Real-Time Database Sync

[简体中文](README.md) | **English**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen.svg)](https://spring.io/projects/spring-boot)

A ready-to-run web application that keeps **schema and data** in sync **across heterogeneous databases** in real time. Start a single jar, click a few times in your browser, and Oracle tables stream continuously into PostgreSQL, or MySQL deltas land live in Dameng — **no Kafka, no ZooKeeper, not a single line of code.**

- Repository: <https://github.com/vfaner/synctool>
- China mirror: <https://gitee.com/super_rgh/synctool>
- Demo video: <https://www.bilibili.com/video/BV1iHYJ6vEEd> (Bilibili, ~10 min; narration in Chinese)

Stack: Spring Boot 2.7 monolith + Thymeleaf server-side rendering + Quartz scheduling + embedded H2 metadata store. **Zero external dependencies, fully usable on an air-gapped intranet.**

---

## Table of Contents

- [Screenshots](#screenshots)
- [Features](#features)
- [Comparison With Existing Tools](#comparison-with-existing-tools)
- [Supported Databases](#supported-databases)
- [Deployment](#deployment)
- [Login & Roles](#login--roles)
- [Workflow](#workflow)
- [Concurrency & Consistency Design](#concurrency--consistency-design)
- [Incremental Detection Strategies](#incremental-detection-strategies)
- [Configuration](#configuration)
- [AI-Assisted Conversion (optional)](#ai-assisted-conversion-optional)
- [Architecture](#architecture)
- [Tests](#tests)
- [Known Limitations](#known-limitations)
- [License](#license)

---

## Screenshots

### Sign-in — one password stands between the jar and your connections

Username and password, with theme and language switchable from the top-right corner at any time. Until the default password is changed, every page carries a red warning at the top — dismissable for the session, but back as soon as the browser reopens, and gone for good only once the password actually changes.

![Sign-in](src/main/resources/static/assets/dataSync_login.png)

### Dashboard — the whole sync posture on one screen

Project count, connection count, synced tables, 24-hour change volume, and a recent activity feed.

![Dashboard](src/main/resources/static/assets/dataSync_kanban.png)

### Dark theme — the whole palette moves, not just the background

One toggle in the top-right corner. Dashboard, cards, tables, and icons all follow, instead of a black background left studded with glaring light-mode controls.

![Dashboard in dark theme](src/main/resources/static/assets/dataSync_kanban_anye.png)

### Database connections — test before you save

Pick a database type and the JDBC URL is generated for you; preview it, test it. For non-bundled drivers, just point at the jar and it is loaded dynamically.

![Database connections](src/main/resources/static/assets/dataSync_db.png)

### Adding a connection — pick the type, the URL writes itself

Fill in host, port, and database name and the JDBC URL appears as you type, so there is no need to remember each vendor's connection-string shape. The password is encrypted on the way into storage, and the connection can be tested before you commit it.

![Adding a connection](src/main/resources/static/assets/dataSync_db_add.png)

### Projects — many pipelines in parallel, start and pause at will

Each project is one source → target pair, independently startable and pausable, with status and last-sync time at a glance.

![Projects](src/main/resources/static/assets/dataSync_xiangmu.png)

### Project detail — per-table selection with visible cursor strategy

Tables / views / stored procedures grouped for selection, with search and bulk actions. Every table's incremental detection strategy is labeled inline — `IDENTITY` and `NONE` are called out prominently, because they mean updates may not propagate.

![Project detail](src/main/resources/static/assets/dataSync_xiangmu_xiangqing.png)

### A read-only account — visible, but not editable

The same project-detail page seen through the `view` user, which holds query permission only. Tables, views, and cursor strategies are all there to read; write actions are not offered to it. Every other screenshot here is taken as `admin`.

![Project detail as a read-only user](src/main/resources/static/assets/dataSync_xiangmu_xiangqing_view.png)

### Change log — every change is traceable

Object name, change type, affected row count, elapsed time, and full error detail.

![Change log](src/main/resources/static/assets/dataSync_log.png)

### AI providers — several configured, exactly one active

Enabling another switches the current one off. The list shows the protocol, the model, and the last probe result; rendering it never reaches out to the network.

![AI providers](src/main/resources/static/assets/dataSync_ai.png)

### Adding a provider — probe the endpoint before saving

The key is encrypted like a database password. The probe sends a real request rather than a TCP check — a wrong key, a misspelled model, and a base URL that is one path segment off are invisible to anything less.

![Adding a provider](src/main/resources/static/assets/dataSync_ai_add.png)

---

## Features

| Area | Capability |
|---|---|
| **Project management** | Configure source/target databases; run many projects in parallel without interference |
| **Connection testing** | Verify connectivity before saving; preview the auto-assembled JDBC URL |
| **Object selection** | Tables / views / stored procedures, all selected by default, with search and bulk checkboxes |
| **Sync scope** | Table schema, table data, indexes, views, stored procedures and functions |
| **Auto object creation** | Missing objects are created in the target using the target's dialect |
| **SQL dialect adaptation** | Type mapping, function-name conversion, identifier quoting, pagination syntax, stored-procedure wrapping |
| **Real-time sync** | Polling (2s default), or precise orchestration via Cron expressions |
| **Crash recovery** | Resumes from the last cursor after a restart; changes made while down are backfilled |
| **Start/stop control** | Start or pause anytime; manual "Sync Now" supported |
| **Change records** | Object, change type, row count, duration, and error detail for every run |
| **Dashboard** | Projects, connections, synced tables, 24-hour change volume, recent activity |
| **i18n** | Chinese / English switch; default language inferred from browser timezone |
| **Theming** | Light/dark toggle; follows the OS until the user picks one |
| **Fully local frontend** | No CDN, no webfont, zero outbound requests at runtime — works offline |

### Frontend Design

Indigo-to-pink gradient accent (`#4f46e5` → `#ec4899`), soft shadows, glassmorphic navbar. Styles are hand-written CSS plus design tokens (CSS variables) — **no framework, no build step.**

**Theme switching**

- The theme lives on `<html data-theme="dark|light">` and only overrides CSS variables, so components need no second set of dark rules
- Storage key `synctool-theme` (localStorage)
- **Follows the OS** `prefers-color-scheme` until the user has chosen explicitly, and reacts live to OS theme changes; once the toggle is clicked, the user's choice wins permanently
- An inline `<head>` script applies the theme before first paint, so dark-mode users never see a white flash

**Default language resolution** (highest priority first)

| Priority | Source | Notes |
|---|---|---|
| 1 | Language cookie | An explicit click on the language button always wins |
| 2 | **Browser timezone** | `Asia/Shanghai`, `Asia/Hong_Kong`, `Asia/Taipei`, `Asia/Macau`, … → Chinese; everything else → English |
| 3 | `Accept-Language` | Fallback for the very first request, before the timezone is reported |
| 4 | Simplified Chinese | Final default |

**Why timezone outranks `Accept-Language`:** overseas Chinese users often run an English-language browser while sitting in a Chinese timezone. Timezone is the better signal for "which language do you actually want to read."

Because rendering is server-side, the language must be decided before render, so a frontend script probes the timezone and writes the `SYNCTOOL_TZ` cookie; the server's `TimezoneAwareLocaleResolver` reads it to pick the Locale. On a first visit, if the rendered language disagrees with the timezone inference, the page refreshes once; after an explicit language choice it never refreshes again.

**Offline / intranet ready** — every frontend asset lives in the repo, and **no external request is made at runtime**:

| Asset | Notes |
|---|---|
| `css/app.css` | Hand-written styles, including design tokens and the dark theme |
| `js/theme.js`, `js/app.js` | Vanilla JS — no jQuery, no framework |
| `vendor/css/bootstrap-icons.min.css` + `vendor/fonts/*.woff2` | Icon font, self-hosted |
| `vendor/favicon.svg` | Inline gradient SVG |

Typography uses the system font stack (`PingFang SC` / `Microsoft YaHei` / …) — **no webfont**. Small graphics like dropdown arrows are inline `data:` URIs. Total static payload is roughly 440 KB. To verify: fetch any page's HTML and count external `http(s)` URLs in `src`/`href` — the answer is **0**.

---

## Comparison With Existing Tools

### At a glance

| Dimension | **SyncTool** | Debezium + Kafka | Canal | Flink CDC | DataX | Kettle | SymmetricDS | Navicat/DBeaver transfer |
|---|---|---|---|---|---|---|---|---|
| **Deployment** | **One jar** | Kafka + Connect + ZK/KRaft | Canal Server (+MQ) | Flink cluster (JM/TM) | CLI scripts | Desktop + repository | Engine on every node | Desktop client |
| **External deps** | **None** | Kafka, ZooKeeper | ZooKeeper (cluster) | Flink, checkpoint store | None (but JSON jobs) | JVM + plugins | Triggers in source DB | None |
| **How you configure it** | **Click in a web UI** | YAML/REST + consumer code | Config file + client code | SQL/DataStream code | JSON job files | Drag-and-drop ETL | properties + create triggers | Wizard |
| **Continuous incremental sync** | ✅ polling/Cron | ✅ log-based | ✅ binlog | ✅ log-based | ❌ one-shot batch | ⚠️ roll your own scheduling & delta logic | ✅ trigger-based | ❌ one-shot |
| **Schema (DDL) sync** | ✅ **auto-creates tables/indexes/views/procs** | ⚠️ emits DDL events; applying them is your job | ⚠️ events only | ⚠️ custom code | ❌ tables must pre-exist | ⚠️ manual mapping | ⚠️ limited | ✅ but one-shot |
| **Heterogeneous dialect conversion** | ✅ types/functions/quoting/paging/procs | ❌ DIY | ❌ | ⚠️ partial | ⚠️ limited type mapping | ⚠️ manual | ⚠️ limited | ⚠️ one-shot mapping |
| **Chinese domestic databases** | ✅ **DM / KingBase / GBase / Oscar / OpenGauss** | ❌ essentially unsupported | ❌ MySQL only | ⚠️ a few | ⚠️ needs custom plugins | ⚠️ generic JDBC only | ⚠️ limited | ⚠️ partial |
| **Intrusiveness to source** | **Read-only queries, zero intrusion** | binlog/WAL + replication privileges | binlog required | binlog/WAL required | read-only | read-only | **must create triggers** | read-only |
| **Idempotency / resume** | ✅ PK upsert + persisted cursor | ✅ offsets | ✅ | ✅ checkpoints | ❌ | ❌ DIY | ✅ | ❌ |
| **Built-in monitoring** | ✅ dashboard + change log | Prometheus/Grafana required | DIY | Flink UI (job-level) | logs | limited | Web console | ❌ |
| **Time to first sync** | **Minutes** | High | Medium-high | High | Medium | Medium | Medium-high | Low (but no continuous sync) |
| **Air-gapped** | ✅ no outbound calls | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Best fit** | Small/medium, departmental, domestic-DB migration | Large-scale streaming | MySQL ecosystem | Large-scale streaming | Bulk offline loads | Complex ETL | Multi-master replication | Ad-hoc data moves |

> Legend: ✅ native support　⚠️ partial / extra work required　❌ not supported

### Five differences that actually matter

**1. "One jar" versus "a whole platform"**

Debezium and Flink CDC are excellent streaming frameworks, but standing up a single MySQL → PostgreSQL pipeline means Kafka, Kafka Connect, a coordination service, and then a consumer you write yourself to translate events into target-side DML. The SyncTool equivalent is: `java -jar synctool.jar`, open a browser, create two connections, create a project, click Start. **When the size of the sync need doesn't justify the operational cost of a streaming platform, this gap is decisive.**

**2. Schema sync is a first-class feature, not homework left for you**

Most CDC tools solve only the data stream; the target tables are yours to create. DataX explicitly requires them to pre-exist. SyncTool reads source metadata and creates tables, indexes, views, and stored procedures **in the target's dialect**, then propagates the diff after source-side DDL changes. In a heterogeneous migration, DDL translation and type mapping is usually more work than moving the rows.

**3. Built for Chinese domestic databases and localization migrations**

Dameng (DM), KingBase, GBase, Oscar, and OpenGauss are built-in first-class options — not "you can probably reach it over generic JDBC," but dedicated dialect implementations: the `MERGE INTO ... FROM DUAL` upsert form, type ceilings (Oracle `VARCHAR2` 4000), function-name differences, and identifier quoting rules are all handled. Oracle/SQL Server → domestic-DB replacement is this tool's home turf, and it happens to be exactly where the Debezium and Canal ecosystems are weakest.

**4. Zero intrusion into the source database**

SymmetricDS requires triggers in the source. Debezium, Canal, and Flink CDC require binlog / WAL logical replication to be enabled plus replication privileges — on many production databases that is a change request with an approval workflow attached. SyncTool needs one **read-only account** and derives deltas from cursor-column queries. The source's schema and configuration are untouched.

**5. One-shot data movement versus staying in sync**

Navicat's and DBeaver's "data transfer", and DataX, solve "copy this data across, once." SyncTool solves "keep both sides consistent, indefinitely": resume from the cursor after a restart, backfill changes that happened while down, and make every row write idempotent. Those are two different problems.

### When *not* to use SyncTool

Being honest about the boundaries:

- **You need millisecond latency or strict change ordering** → use Debezium / Flink CDC. A polling design's floor on latency is the poll interval.
- **You need physical-delete capture on large tables** → without a source-side audit table, delete detection requires comparing full primary-key sets, so it's only enabled below `full-compare-max-rows`.
- **One-time initial load of hundreds of millions of rows** → tools built for bulk throughput, like DataX, will be faster.
- **You need complex ETL transformation (cleansing, aggregation, multi-stream joins)** → use Kettle / Flink. SyncTool does **synchronization**, not **transformation**.
- **Bidirectional multi-master replication** → use SymmetricDS. This tool assumes it is the sole writer to the target.

---

## Supported Databases

MySQL, MariaDB, Oracle, SQL Server, DB2, PostgreSQL, OpenGauss, **Dameng (DM)**, **KingBase**, **GBase**, **Oscar**, H2, plus **custom databases** (supply a JDBC URL, driver class name, and driver jar path — loaded dynamically at runtime).

Only **MySQL / PostgreSQL / H2** drivers are bundled. For anything else, fill in the driver jar path in the connection form; the tool loads it with a dedicated `URLClassLoader` and registers it with `DriverManager` through a `DriverShim`. The upside: **the distribution doesn't have to ship a pile of commercial drivers, and driver version conflicts can't pollute the application classloader.**

---

## Deployment

### Requirements

| Item | Requirement |
|---|---|
| JDK | **17 or newer** |
| Maven | 3.6+ (build time only) |
| Memory | ≥ 512 MB heap recommended |
| Port | `8080` by default |
| Disk | Metadata store + logs + snapshots; reserve ~1 GB |

### 1. Build

```bash
git clone https://github.com/vfaner/synctool.git
# In mainland China, use the mirror:
# git clone https://gitee.com/super_rgh/synctool.git

cd synctool
mvn clean package -DskipTests
```

Artifact: `target/synctool.jar` (executable fat jar).

### 2. Run

```bash
java -jar target/synctool.jar
```

Open <http://localhost:8080>.

The first launch creates the following under the **current working directory**:

| Directory | Contents |
|---|---|
| `./data` | The tool's own metadata (H2 file DB: connections, projects, cursors, locks, change log) |
| `./logs` | Runtime logs |
| `./snapshots` | Metadata snapshot directory (override with `sync.snapshot-dir`) |

> ⚠️ These are **relative paths**. Always start from the same directory, or override them with absolute paths — otherwise a restart won't find your existing data.

### 3. Production configuration (important)

Create `application.yml` next to the jar:

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:h2:file:/opt/synctool/data/synctool;MODE=MySQL;AUTO_SERVER=TRUE

sync:
  poll-interval: 2000              # polling interval in ms
  snapshot-dir: /opt/synctool/snapshots
  batch-size: 500
  fetch-size: 1000
  safety-lag-ms: 1000
  row-count-audit-interval-ms: 60000
  full-compare-max-rows: 20000
  lock-ttl-ms: 300000
  crypto-password: replace-with-your-own-strong-secret   # ← MUST change
  crypto-salt: replace-with-your-own-hex-salt            # ← MUST change

logging:
  file:
    path: /opt/synctool/logs
```

Start with:

```bash
java -jar synctool.jar --spring.config.location=file:./application.yml
```

> 🔐 **Security note:** `sync.crypto-password` and `sync.crypto-salt` encrypt the stored database passwords. **The distribution ships with defaults; you must change them in production.** After changing them, previously stored passwords can no longer be decrypted and must be re-entered in the UI. `crypto-salt` must be a valid hexadecimal string.

### 4. Loading non-bundled drivers

For Oracle, SQL Server, DB2, DM, KingBase, and friends, place the vendor jar on the server:

```bash
mkdir -p /opt/synctool/drivers
cp ojdbc8.jar DmJdbcDriver18.jar kingbase8-8.6.0.jar /opt/synctool/drivers/
```

Then, when creating a connection on the **Database Connections** page, fill in the **driver jar path** (e.g. `/opt/synctool/drivers/ojdbc8.jar`) and the **driver class name** (auto-filled when you pick a preset type). Click **Test Connection** to confirm it loads, then save.

### 5. Running as a service

**Option A: systemd (recommended)**

`/etc/systemd/system/synctool.service`:

```ini
[Unit]
Description=SyncTool Database Sync
After=network.target

[Service]
Type=simple
User=synctool
WorkingDirectory=/opt/synctool
ExecStart=/usr/bin/java -Xms512m -Xmx1g -jar /opt/synctool/synctool.jar \
  --spring.config.location=file:/opt/synctool/application.yml
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now synctool
sudo systemctl status synctool
```

**Option B: nohup (quick trial)**

```bash
cd /opt/synctool
nohup java -jar synctool.jar > /dev/null 2>&1 &
```

### 6. Upgrading

```bash
sudo systemctl stop synctool
cp target/synctool.jar /opt/synctool/synctool.jar
sudo systemctl start synctool
```

The metadata store uses `ddl-auto: update`, so its schema evolves automatically. **Back up `./data` before upgrading.** Source-side changes that occur while the tool is down are backfilled by the cursor mechanism on restart — nothing is lost.

---

## Login & Roles

Every page and every endpoint requires a signed-in session. There is no anonymous entry point.

On first start two accounts are seeded into the `app_user` table of the metadata store (skipped entirely if the table already has rows — nobody's password is ever reset):

| Username | Role | Initial password | Permissions |
|---|---|---|---|
| `admin` | Administrator | `123456` | Everything |
| `view` | Viewer | `123456` | Read-only |

> ⚠️ **Change both passwords immediately after your first sign-in.** The jar is publicly downloadable, so the initial password is not a secret. Any account still on it sees a persistent yellow banner at the top of every page; clicking it jumps to the change-password form.

### What the two roles differ on

Authorization is not a list of paths — it is decided **by HTTP method**. Every write in this tool is a POST and no GET mutates state, so there is exactly one rule: **a POST requires the administrator role.** New endpoints therefore cannot be forgotten.

- **Administrator**: create/edit/delete database connections, projects and AI providers; select sync objects, start/pause sync, sync now, reset progress; draft and save procedure conversions; clear the change log.
- **Viewer**: sees every page and all of the data (dashboard, connection list, the checked state on project detail, the change log, and the SQL on the conversion review page — all viewable, selectable and copyable), but no write control is rendered anywhere, and hand-crafting the request to hit the endpoint directly is refused too. The config block on project detail is kept visible and natively greyed out rather than hidden, because the selection state is itself useful read-only information.

Both roles can change their own password.

### Changing your own password

Click your username in the top-right → **Change password**, or go straight to `/account/password`. The current password is required; the new one must be at least 6 characters and must differ from the current one. The change signs you out immediately — sign back in with the new password.

Login passwords are stored as one-way BCrypt hashes, unlike the database and AI credentials, which must be recoverable in cleartext to hand to a driver. **A forgotten password cannot be recovered.** If it really is lost, delete that row from `app_user`; the initial password is re-seeded on the next restart.

---

## Workflow

1. **Database Connections** → create a source and a target connection → click **Test Connection**
2. **Projects** → create a project → pick the source and target
3. Open **project detail** → check the tables/views/procedures to sync → configure sync options → save
4. Click **Sync Now** for a one-off verification, or **Start Sync** to begin continuous polling
5. Inspect each run in the **Change Log**

---

## Concurrency & Consistency Design

This is the heart of the tool and deserves its own section.

### The incremental window is a closed interval

Each cycle, when syncing table data:

1. **First**, read `MAX(cursor column)` from the source as this window's upper bound (the high watermark)
2. Query rows where `cursor > last cursor AND cursor <= this watermark`
3. Write to the target and commit
4. **Only after a successful commit**, advance the cursor to the watermark

The upper bound in step 2 is the crux. Without it, a long-running read could advance the cursor past data it never actually read — those rows would be skipped forever. With the bound in place, rows written to the source during the sync fall naturally outside the window and get picked up next cycle.

### The cursor advances only after data is committed

Source and target are two separate heterogeneous databases; there is no cross-database transaction. So the chosen order is: **commit target data first, then persist the cursor.**

- Crash between the two → the same window is replayed next time
- Crash before the target commit → the transaction rolls back, and it is likewise replayed

Neither case loses data. The cost is that a row may be delivered twice, which the next point absorbs.

### Every write is idempotent

Every row is written via a primary-key-based upsert, so repeated execution converges to the same state:

| Database | Statement |
|---|---|
| MySQL / MariaDB | `INSERT ... ON DUPLICATE KEY UPDATE` |
| PostgreSQL family | `INSERT ... ON CONFLICT (pk) DO UPDATE` |
| Oracle / DM | `MERGE INTO ... USING (SELECT ? FROM DUAL)` |
| SQL Server | `MERGE ... WITH (HOLDLOCK)` |
| DB2 | `MERGE INTO ... USING (VALUES (?))` |
| Generic / custom | UPDATE-then-INSERT (fallback where no native upsert exists, with unique-conflict retry) |

This turns "at-least-once delivery" into "effectively exactly-once." Deletes are likewise primary-key-conditional, so replaying them has no side effects.

> **Tables without a primary key:** existing rows cannot be identified, so idempotency cannot be guaranteed. The tool warns explicitly and recommends adding a primary key.

### Timestamp safety lag

A timestamp is fixed when the statement executes, but a row only becomes visible to us when its transaction commits. A source transaction that **starts early and commits late** may carry a timestamp below a watermark we have already passed — and would then be skipped forever.

So when persisting the watermark, we roll it back by `sync.safety-lag-ms` (1 second by default). The cost is that a few already-synced rows inside that window are re-delivered, which idempotent writes absorb; the benefit is that late-committing transactions are never lost.

### Single-instance execution: three layers of locking

| Layer | Coverage | Gap |
|---|---|---|
| `@DisallowConcurrentExecution` | The same job never fires concurrently within one scheduler | Quartz only, not manual runs; lost on process restart |
| JVM `ReentrantLock` (per project) | Manual "Sync Now" and scheduled runs are mutually exclusive in-process | Useless across processes |
| Database lock row (with expiry) | Cross-process, cross-node | — |

The database lock is the layer that survives restarts: in-memory locks die with the process, and with only in-memory locking a new instance could not tell whether a hard-killed predecessor is still running. The lock row carries a lease (`sync.lock-ttl-ms`, 5 minutes by default) so a crashed instance's lock can be taken over instead of blocking the project forever; on startup, an instance also proactively releases locks it left behind.

Acquisition uses a conditional UPDATE (`WHERE lock_owner IS NULL OR lock_owner = ? OR lock_expires_at < ?`), so when two instances race, only one UPDATE can match — they can never both hold the lock.

### Recovering from schema changes

Metadata snapshots are persisted in the `metadata_snapshot` table, and each object updates its own snapshot immediately after its DDL is applied successfully. Therefore:

- DDL changes that happened on the source while the tool was down are detected on restart via snapshot comparison
- If a run crashes partway, already-applied objects keep their snapshots and the rest are re-detected next cycle
- `CREATE TABLE` tolerates "already exists" errors, making schema sync replayable too

### Row-count auditing

A cursor can prove "how far I read," but not "the target still holds those rows." So every `sync.row-count-audit-interval-ms` (60 seconds by default), a row-count audit reconciles the target's actual row count against what the cursor claims was delivered, recording any drift in the change log.

---

## Incremental Detection Strategies

### What actually triggers a sync

Incremental sync is one query:

```sql
SELECT * FROM table WHERE cursor_col > last_cursor AND cursor_col <= watermark ORDER BY cursor_col
```

**Whether a row gets synced depends on one thing only: whether its cursor column value has risen above the last recorded value.** Everything below follows from that:

- Cursor on a **single numeric primary key** (`id`): an UPDATE does not change `id`, so the row's cursor value stays put → **updates never propagate**. Only inserts get through.
- Cursor on a **creation timestamp** (`create_time`): same story — a creation time does not move when the row is modified → **updates never propagate**.
- Cursor on a **last-modified timestamp** (`update_time`): updates are detected, but **only if that column is actually changed**. If the DDL has no `ON UPDATE CURRENT_TIMESTAMP` and your statement is `UPDATE t SET name = 'x' WHERE id = 1` (never setting `update_time`), the column does not move and the row is never picked up.

In other words, **the cursor column must be one that increases whenever a row is touched.** Otherwise modifications are invisible to the sync. This is not a defect; it is the unavoidable price of doing incremental replication with ordinary queries — without reading the source's binlog and without installing triggers. Nothing in the source tells us a row was modified, so the column value has to say it.

### Auto-detection order

Chosen in descending order of reliability:

| Strategy | Trigger | Inserts | Updates |
|---|---|:---:|---|
| `TIMESTAMP` | A temporal-typed column matching the **last-modified** naming convention | ✅ | ✅ provided that column really is updated |
| `IDENTITY` | A column matching the **creation-time** convention, or a single numeric primary key | ✅ | ❌ never detected |
| `FULL_COMPARE` | No usable cursor column, and row count ≤ `sync.full-compare-max-rows` (default 20000) | ✅ | ✅ full-table upsert every cycle |
| `NONE` | No usable cursor column and the table is too large | ❌ | ❌ initial full load only, then skipped with a stated reason |

Names accepted as **last-modified** (16, resolve to `TIMESTAMP`):

`UPDATE_TIME` `UPDATED_AT` `UPDATETIME` `UPDATED_TIME` `LAST_MODIFIED` `LASTMODIFIED` `LAST_UPDATE` `LAST_UPDATED` `MODIFY_TIME` `MODIFIED_AT` `MODIFIED_TIME` `GMT_MODIFIED` `ROW_VERSION` `ROWVERSION` `SYS_UPDATE_TIME` `DATA_CHANGE_TIME`

Names accepted as **creation-time** (7, resolve to `IDENTITY`, updates undetectable):

`CREATE_TIME` `CREATED_AT` `CREATETIME` `CREATED_TIME` `GMT_CREATE` `INSERT_TIME` `ADD_TIME`

Matching rules: case-insensitive, `-` treated as `_`, matched as a **substring** (`biz_update_time_utc` counts as a hit), and the lists are tried in the order above so the most specific convention wins. **The column's type must also genuinely be temporal** — a `VARCHAR` named `update_time` is not used as a cursor, because string comparison ordering is unreliable. By the same rule `ROW_VERSION` / `ROWVERSION` only matches when its type really is temporal; SQL Server's `rowversion` is a binary type and does not qualify.

### Setting the cursor column manually

**You can set a cursor column per table** on the project detail page; it takes precedence over auto-detection. But what you pick is a **column**, not a strategy — the column's type decides which strategy you end up with:

| Type of the chosen column | Resulting strategy | Note |
|---|---|---|
| Temporal (`DATETIME` / `TIMESTAMP` / `DATE` / `TIME`) | `TIMESTAMP` | Detects updates |
| Numeric (`INT` / `BIGINT` / `DECIMAL`, …) | `IDENTITY` | **Still cannot detect updates** |
| Anything else, or the column does not exist | Ignored, falls back to auto-detection | Logged as a WARN |

So there is **no way** to force a table into `FULL_COMPARE` from the UI: it is selected automatically only when no usable cursor column exists at all and the table is small enough.

Tables resolving to `IDENTITY` or `NONE` are flagged on the project detail page along with the reason, because it means updates may not propagate.

### When updates are not propagating

**The durable fix** — give the source table a last-modified column that genuinely moves:

```sql
-- MySQL
ALTER TABLE your_table ADD COLUMN update_time DATETIME
  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
```

With that name you do not need to configure anything; the next cycle resolves it to `TIMESTAMP` automatically. On databases without an equivalent "rewrite on update" clause, the writer (application code or a trigger) has to maintain the column.

**Catching up on updates already missed** — **stop the sync first**, then hit **Reset progress** on the project detail page (resetting is refused while the project is enabled, with a message telling you to stop it). This deletes all cursor progress and structure snapshots for the project, so the next cycle performs a full load: the table is re-read and every row upserted, previously missed updates go across, and the structure is re-baselined. It is a one-time catch-up though: unless the cursor column itself is fixed, the next UPDATE will be missed again.

---

## Configuration

Under `sync.*` in `application.yml`:

| Key | Default | Description |
|---|---|---|
| `poll-interval` | `2000` | Polling interval (ms) |
| `snapshot-dir` | `./snapshots` | Metadata snapshot directory |
| `batch-size` | `500` | Rows per JDBC batch |
| `fetch-size` | `1000` | Source result-set fetch size |
| `max-retries` | `3` | Consecutive failures before a task is marked ERROR |
| `safety-lag-ms` | `1000` | Timestamp watermark rollback; see above |
| `row-count-audit-interval-ms` | `60000` | Row-count audit interval (ms) |
| `full-compare-max-rows` | `20000` | Row ceiling for full-table comparison |
| `lock-ttl-ms` | `300000` | Sync lock lease duration (ms) |
| `crypto-password` | *(default)* | Password encryption key — **must be changed in production** |
| `crypto-salt` | *(default)* | Encryption salt (hex) — **must be changed in production** |
| `ai.enabled` | `true` | Whether AI-assisted conversion may be configured. `false` removes the menu and its endpoints |

Connection passwords are stored encrypted with AES-256 via Spring Security Crypto, marked with an `enc:` prefix to avoid double encryption, and remain compatible with plaintext written before encryption was enabled.


---

## AI-Assisted Conversion (optional)

Textual substitution only goes so far with incompatible stored-procedure syntax. `SqlBodyConverter`
deliberately refuses to translate procedural control flow, because getting it wrong yields SQL that
is valid but returns different results — a silent wrong answer, which is far worse than a failure
that names the object. The `AI` menu offers another route: let a model draft a candidate that you
review before it is stored.

**The boundaries here are hard:**

- AI **only produces candidates**. It never participates in a sync; `StructureSyncService` calls no
  AI code at all.
- A candidate is written to the project's `ddlOverrides` only after you confirm it, using the manual
  override path that was already supported.
- With no provider enabled, the tool makes **no outbound request whatsoever**, exactly as before.
- Setting `sync.ai.enabled=false` removes the feature entirely — menu and REST endpoints alike.

### Configuration

The `AI` page accepts several providers, of which **exactly one is active at a time**. Enabling
another switches the current one off; the exclusivity is enforced server-side inside a transaction,
so two open browser tabs cannot leave two providers enabled.

| Field | Notes |
|---|---|
| Protocol | `OpenAI-compatible` or `Anthropic`. Nearly every self-hosted endpoint is the former |
| Base URL | Endpoint root. Vendors disagree about whether `/v1` belongs here, so the form shows the request path that will actually be used |
| Model | Model name, e.g. `deepseek-chat` |
| API key | Encrypted with AES-256 like a database password; leave blank when editing to keep the stored value |
| Max tokens / timeout | A long procedure needs room for the whole rewritten body |

Every provider can be **probed**. The probe sends a real request with `max_tokens: 1`: a TCP or
`HEAD` check would report success for a wrong key, a misspelled model, or a base URL that is one
path segment off — the three things that actually go wrong. The outcome is stored and shown as a
status badge, so rendering the list itself never reaches out to the network.

Enabling a provider makes AI-assisted conversion available. A failed probe does **not** block
enabling — a transient network problem should not make the setting unsavable — and the list shows
a red badge instead.

### Conversion review

The `Conversion review` entry on the project detail page (it appears once both the source and the
target are configured) lists every view and stored procedure the project has selected, marking which
ones already carry a manual override. Opening one object leads to the review page:

| Area | Contents |
|---|---|
| Side-by-side | The source's original definition on the left, `SqlBodyConverter`'s mechanical conversion on the right — that is, **the statement the sync would actually execute if you did nothing** |
| Uncertainties | The model's own list of what it cannot guarantee is equivalent, placed above the SQL. This is the first thing to read |
| Editor | What gets stored. If an override already exists it opens with that content, which a fresh mechanical conversion will not overwrite |
| Syntax check | Takes the editor's current content and really creates it on the **target**, then drops it |

The mechanical column calls the same `StructureSyncService` methods the sync itself uses, not a
second simplified implementation — the moment the two drift, this page is lying, and "the SQL shown
to the reviewer differs from the SQL the sync runs" is worse than showing nothing.

Two things need saying plainly:

**The uncertainty list is worth more than the SQL.** If a two-hundred-line procedure comes back with
no uncertainties at all, that is a signal the model did not look carefully — not a signal the
conversion is good. Read the list before reading the SQL.

**The syntax check writes to the target.** It really creates an object there named
`SYNCTOOL_AI_CHECK_<timestamp>`, then drops it in a `finally`. This is the only place any AI-related
code writes to the target, so it runs only when you click the button, and only after a confirmation
dialog; the sync path never calls it. Why create it for real: no database offers a portable
"parse but do not execute" call, and Oracle-family products create PL/SQL that fails to compile as
an `INVALID` object rather than raising an error — hence the `USER_ERRORS` readback for those
products, without which every check would report a false success.

Passing the check **only proves the target accepts this DDL**, not that it behaves the same. A
recursive object is verified only as a renamed copy, whose self-call resolves to whatever already
exists on the target; that is reported explicitly as a caveat.

### Before you use it

Probing and conversion both send requests to the endpoint you configure, and a conversion includes
**the procedure's source code**, which often encodes business rules and even the full table
structure. On an intranet or a regulated deployment, prefer a local or in-house inference endpoint,
and confirm you are permitted to send this code off-site.

Also note that **no tool can guarantee the converted procedure behaves identically**, and an LLM is
no exception. Procedure differences are usually semantic rather than syntactic: cursor behaviour,
implicit transaction boundaries, `NO_DATA_FOUND`-style exception control flow, the
non-determinism of pagination without an explicit sort, or `NULL` in string concatenation — treated
as an empty string by Oracle but poisoning the whole expression in MySQL. So the role here is
**drafting**, not **guaranteeing**: every candidate needs human review, and critical procedures
should be tested against the target for real.

---

## Architecture

```
com.synctool
├── config           Configuration: i18n, Quartz, Jackson, SecurityConfig, SyncProperties
├── controller       MVC controllers; controller/api holds the REST endpoints
├── service
│   ├── connection   DataSourceManager, DriverLoader, DriverShim, connection testing
│   ├── metadata     MetadataReader dialect implementations + snapshot service
│   ├── monitor      ChangeDetector (schema diffing), CursorStrategyResolver
│   ├── converter    SqlDialect implementations, type mapping, SQL body conversion
│   ├── sync         SyncEngine, DataSyncService, StructureSyncService, DdlExecutor
│   ├── ai           Provider config, shared HTTP layer, candidate drafting, candidate validation, review orchestration
│   ├── task         Quartz scheduling, three-layer locking, context assembly, startup recovery
│   └── auth         Account seeding, BCrypt password change, UserDetailsService
├── model            JPA entities and enums
├── repository       Spring Data JPA
├── dto              SyncConfig, ChangeEvent, SyncResult, meta/* metadata models
└── util             CryptoUtil
```

### One deliberate `@Transactional` decision

`SyncStateWriter`, `SyncLockStore`, and `SyncTaskStore` are separate beans rather than methods on `SyncEngine` / `SyncLockService`. The reason is that Spring's `@Transactional` is proxy-based: self-invocation within the same class bypasses the proxy, and `REQUIRES_NEW` and `@Modifying` queries lose their transactional semantics. Only a cross-bean call makes those semantics real — and cursor advancement and lock acquisition depend on exactly that.

---

## Tests

```bash
mvn test
```

223 unit tests, covering:

- **Dialect invariants** — every dialect produces a conflict-handling idempotent upsert; bind order matches placeholder count; type mapping never exceeds per-product ceilings (Oracle `VARCHAR2` 4000, SQL Server 4000, DB2 DECIMAL 31, precision-less `NUMBER` never yields `DECIMAL(0,0)`); declared precision is clamped to the ceiling without losing fractional digits; non-portable defaults are dropped rather than emitted as invalid DDL
- **SQL body rewriting** — string literals, quoted identifiers, line comments, and block comments are never rewritten; escaped quotes inside a literal do not end it early; unterminated literals are preserved verbatim; `SUBSTR` → `SUBSTRING` does not double-hit itself
- **Cursor strategies** — resolution priority; `IDENTITY` correctly flagged as "may miss updates"; graceful downgrade rather than an error when a configured column becomes invalid; a `VARCHAR` `update_time` is never misused
- **Cursor serialization** — timestamps round-trip as UTC ISO-8601 without losing millisecond precision; oversized numbers downgrade to `BigDecimal`; corrupt values are treated as "not yet synced" instead of throwing
- **Password encryption** — round-trip, no double encryption, backward compatibility with legacy plaintext, distinct ciphertexts for identical passwords
- **AI configuration** — enabling one provider necessarily disables every other (the enabled set is asserted to be exactly one); the global switch masks even an enabled provider; keys are stored encrypted, a blank field on edit keeps the stored one, and the probe receives the plaintext rather than the ciphertext; changing the endpoint clears a stale "reachable" badge; the key appears neither in the probe result nor in the edit page source; OpenAI sends `Authorization: Bearer` while Anthropic sends `x-api-key` and no `Authorization`; a trailing slash or an already-complete endpoint path never produces a doubled path
- **AI reply parsing** — SQL is recovered from bare JSON, from a ```` ```json ```` fence, and from half a fence truncated by the token limit; a reply that ignores the output format entirely still has its SQL used, but gains an extra uncertainty entry (a model that would not follow the format probably did not follow "do not invent columns" either); when the model declines the conversion outright its stated reasons are kept rather than discarded as a network error; an empty reply never silently clears the editor; an unconfigured provider throws rather than masquerading as "conversion failed"
- **Candidate validation** (against real H2) — a same-named object is **byte-identical before and after** a check, including the two forms easiest to get wrong (`CREATE OR REPLACE` and a schema-qualified name); no object is left behind afterwards, and a failed `CREATE` is cleaned up just the same; a statement whose `CREATE` header cannot be parsed is refused rather than forwarded verbatim to the target; the temporary name stays within Oracle's 30-byte limit and two consecutive checks never collide; "the database is unreachable" and "the statement was rejected" do not share one message
- **Override storage** — the key matches the one `StructureSyncService` actually looks up (`FUNCTION` folds onto `PROCEDURE`); a blank override is refused (an empty-string override is worse than none — the sync would execute it and the object would silently disappear); deleting a key that does not exist writes nothing; overrides whose object was dropped at the source or deselected in the project are flagged as orphans and can be cleared
- **Page rendering** — every conditional branch of both review pages (override present/absent, model available/not, same dialect family/not, orphans/none) is actually rendered, asserting that no `??` appears in the output — a message key added to only one bundle shows up here as `??key_en_US??`; an object name containing a dot is not truncated as a file extension by Spring
- **Login & roles** — the initial accounts are seeded only into an empty table, so a restart does not re-seed and a table that already has rows has nobody's password reset; the stored hash starts with `$2a$` and does not contain the cleartext; the five rejection reasons for a password change (wrong current password, too short, mismatch, same as the current one, no such account) each return their own message key; the "using the initial password" flag flips to false after a successful change
- **Authorization rules** — an unauthenticated page request redirects to `/login`, while an unauthenticated `/api/**` request answers 401 JSON rather than handing a login page to `fetch()`; every POST by a viewer is refused; `/account/password` is permitted for both roles, and that rule *must* precede "a POST requires admin" or a viewer would be shown a password form they are forbidden to submit; a POST missing its CSRF token is refused even for an administrator
- **JSON endpoints** — both a rejected request and a server-side exception return 200 with `success:false`, because the caller is `fetch()` and a 500 carrying an HTML error page reaches the user as nothing but a blank toast

There is also an end-to-end script (H2 source and target, 20 assertions) covering initial full load, incremental inserts, incremental updates, idempotency across repeated syncs, DDL column-addition propagation, **matching row counts with no duplicates under concurrent writes**, concurrent invocations rejected by the lock, **writes made during downtime backfilled after restart**, automatic polling, and change-log / cursor-strategy reporting.

---

## Known Limitations

- **Stored procedure conversion** — mechanical differences (function names, identifier quoting, `FROM DUAL`, pagination syntax) are converted automatically, but PL/SQL, T-SQL, and PL/pgSQL have different procedural control-flow constructs, so complex procedures cannot be translated reliably. Such objects are attempted with their original source; on failure the specific error is reported. You can review them one by one on the `Conversion review` page and save a manual override there (with a provider configured, a model can draft the candidate for you) — but **a candidate still needs human confirmation**, and passing the syntax check does not mean semantic equivalence.
- **What AI drafting is and is not** — the model only produces candidates and never participates in a sync; `StructureSyncService` calls no AI code. Semantic equivalence cannot be guaranteed by any tool — cursor behaviour, implicit transaction boundaries, exception control flow, and `NULL` concatenation semantics do not show up in the syntax, so critical procedures must be tested against the target for real.
- **What the syntax check costs** — it really creates a temporary object on the target and then drops it. It runs only on a manual click (behind a confirmation), but killing the process between those two steps leaves a `SYNCTOOL_AI_CHECK_*` object behind, and the list page will not discover it for you.
- **Row-delete detection** — without a source-side audit table this requires comparing the full primary-key sets on both sides, so it is only enabled for tables below `full-compare-max-rows`.
- **Tables without a primary key** — idempotency cannot be guaranteed and replay may produce duplicate rows; the tool warns.
- **Target writers** — the target database is assumed to be written only by this tool.
- **Latency** — a polling design's latency floor is the poll interval. For lower latency, the design can be extended with Debezium-style binlog / LogMiner parsing.

---

## Contributing

Issues and pull requests are welcome:

- GitHub: <https://github.com/vfaner/synctool>
- Gitee: <https://gitee.com/super_rgh/synctool>

If this project helps you, a Star ⭐ is appreciated.

---

## License

Released under the [MIT License](LICENSE) — free for commercial and non-commercial use.

Third-party JDBC drivers are **not** distributed with this project; their license terms are set by their respective vendors. This especially applies to the Oracle, DB2, and Chinese domestic database drivers — please confirm your own usage rights.
