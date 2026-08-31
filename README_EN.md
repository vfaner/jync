# SyncTool · Real-Time Database Sync

[简体中文](README.md) | **English**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen.svg)](https://spring.io/projects/spring-boot)

A ready-to-run web application that keeps **schema and data** in sync **across heterogeneous databases** in real time. Start a single jar, click a few times in your browser, and Oracle tables stream continuously into PostgreSQL, or MySQL deltas land live in Dameng — **no Kafka, no ZooKeeper, not a single line of code.**

- Repository: <https://github.com/vfaner/synctool>
- China mirror: <https://gitee.com/super_rgh/synctool>

Stack: Spring Boot 2.7 monolith + Thymeleaf server-side rendering + Quartz scheduling + embedded H2 metadata store. **Zero external dependencies, fully usable on an air-gapped intranet.**

---

## Table of Contents

- [Screenshots](#screenshots)
- [Features](#features)
- [Comparison With Existing Tools](#comparison-with-existing-tools)
- [Supported Databases](#supported-databases)
- [Deployment](#deployment)
- [Workflow](#workflow)
- [Concurrency & Consistency Design](#concurrency--consistency-design)
- [Incremental Detection Strategies](#incremental-detection-strategies)
- [Configuration](#configuration)
- [Architecture](#architecture)
- [Tests](#tests)
- [Known Limitations](#known-limitations)
- [License](#license)

---

## Screenshots

### Dashboard — the whole sync posture on one screen

Project count, connection count, synced tables, 24-hour change volume, and a recent activity feed.

![Dashboard](src/main/resources/static/assets/dataSync_kanban.png)

### Database connections — test before you save

Pick a database type and the JDBC URL is generated for you; preview it, test it. For non-bundled drivers, just point at the jar and it is loaded dynamically.

![Database connections](src/main/resources/static/assets/dataSync_db.png)

### Projects — many pipelines in parallel, start and pause at will

Each project is one source → target pair, independently startable and pausable, with status and last-sync time at a glance.

![Projects](src/main/resources/static/assets/dataSync_xiangmu.png)

### Project detail — per-table selection with visible cursor strategy

Tables / views / stored procedures grouped for selection, with search and bulk actions. Every table's incremental detection strategy is labeled inline — `IDENTITY` and `NONE` are called out prominently, because they mean updates may not propagate.

![Project detail](src/main/resources/static/assets/dataSync_xiangmu_xiangqing.png)

### Change log — every change is traceable

Object name, change type, affected row count, elapsed time, and full error detail.

![Change log](src/main/resources/static/assets/dataSync_log.png)

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

Chosen in descending order of reliability:

| Strategy | Trigger | Capability |
|---|---|---|
| `TIMESTAMP` | A column named `update_time` / `updated_at` / `last_modified` etc. **of an actual timestamp type** | Detects inserts **and** updates |
| `IDENTITY` | A creation-time column, or a single numeric primary key | Detects inserts **only** |
| `FULL_COMPARE` | No cursor column, and row count below `sync.full-compare-max-rows` | Full-table upsert every cycle |
| `NONE` | No cursor column and the table is too large | Initial full load only, then skipped with a stated reason |

Name matching requires the column's type to genuinely be temporal — a `VARCHAR` named `update_time` will not be used as a timestamp cursor, because string comparison ordering is unreliable.

**You can set a cursor column manually per table** on the project detail page; it takes precedence over auto-detection. Tables resolving to `IDENTITY` or `NONE` are flagged in the UI, because it means updates may not propagate.

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

Connection passwords are stored encrypted with AES-256 via Spring Security Crypto, marked with an `enc:` prefix to avoid double encryption, and remain compatible with plaintext written before encryption was enabled.

---

## Architecture

```
com.synctool
├── config           Configuration: i18n, Quartz, Jackson, SyncProperties
├── controller       MVC controllers; controller/api holds the REST endpoints
├── service
│   ├── connection   DataSourceManager, DriverLoader, DriverShim, connection testing
│   ├── metadata     MetadataReader dialect implementations + snapshot service
│   ├── monitor      ChangeDetector (schema diffing), CursorStrategyResolver
│   ├── converter    SqlDialect implementations, type mapping, SQL body conversion
│   ├── sync         SyncEngine, DataSyncService, StructureSyncService, DdlExecutor
│   └── task         Quartz scheduling, three-layer locking, context assembly, startup recovery
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

43 unit tests, covering:

- **Dialect invariants** — every dialect produces a conflict-handling idempotent upsert; bind order matches placeholder count; type mapping never exceeds per-product ceilings (Oracle `VARCHAR2` 4000, SQL Server 4000, precision-less `NUMBER` never yields `DECIMAL(0,0)`); non-portable defaults are dropped rather than emitted as invalid DDL
- **Cursor strategies** — resolution priority; `IDENTITY` correctly flagged as "may miss updates"; graceful downgrade rather than an error when a configured column becomes invalid; a `VARCHAR` `update_time` is never misused
- **Cursor serialization** — timestamps round-trip as UTC ISO-8601 without losing millisecond precision; oversized numbers downgrade to `BigDecimal`; corrupt values are treated as "not yet synced" instead of throwing
- **Password encryption** — round-trip, no double encryption, backward compatibility with legacy plaintext, distinct ciphertexts for identical passwords

There is also an end-to-end script (H2 source and target, 20 assertions) covering initial full load, incremental inserts, incremental updates, idempotency across repeated syncs, DDL column-addition propagation, **matching row counts with no duplicates under concurrent writes**, concurrent invocations rejected by the lock, **writes made during downtime backfilled after restart**, automatic polling, and change-log / cursor-strategy reporting.

---

## Known Limitations

- **Stored procedure conversion** — mechanical differences (function names, identifier quoting, `FROM DUAL`, pagination syntax) are converted automatically, but PL/SQL, T-SQL, and PL/pgSQL have different procedural control-flow constructs, so complex procedures cannot be translated reliably. Such objects are attempted with their original source; on failure the specific error is reported, and you can supply a manual DDL override in the project configuration.
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
