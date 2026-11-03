# Jync · Heterogeneous Database Real-Time Sync

> **Jync**, pronounced "sync" — **J** for Java; the Chinese name 捷同 means "swift and identical".
> A single-JAR database synchronization engine: **chunked parallel full load, read-only incremental capture, second-level log CDC**, plus SQL-driven ETL and Webhook alerting.

[简体中文](README.md) | **English**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen.svg)](https://spring.io/projects/spring-boot)

![Jync · Heterogeneous Database Real-Time Sync](https://jync.qqmu.com/images/jync_xl.webp)

An out-of-the-box web application that keeps **structure and data** in sync between **heterogeneous databases**: start one jar, pick **34 built-in database types (plus custom types)** in a browser, and a sync or ETL pipeline is running — six of them (Doris, ClickHouse, Hive, Impala, Elasticsearch, MongoDB) are analytic engines usable as real-time sync **sources** (polling / full compare only) and in ETL. No Kafka, no Flink, no ZooKeeper, and not a line of job code.

- **Zero external dependencies**: a Spring Boot monolith with Thymeleaf server-side rendering, Quartz and an embedded H2 metadata store. Runs air-gapped.
- **Full load is not a bulk query import**: keyset chunking, in-table parallelism, streamed result sets, per-chunk commits and checkpoints — single-table throughput reaches the **hundreds of thousands of rows per second** range.
- **Incremental is one indexed query**: the default polling mode reads only rows past the cursor, so one read-only account is enough. Six source families can switch to log CDC for second-level latency.
- **Structure travels with data**: missing tables, indexes, views and procedures are created automatically, with built-in dialect type mapping and function conversion.
- **Resumable by design**: crashes, network drops and killed processes resume from the last checkpoint; every write is idempotent, and changes made while Jync was down are caught up on restart.
- **Failures reach you**: seven fault categories — sync failures, CDC stalls, data drift and more — can be pushed to DingTalk, WeCom, Feishu or a custom Webhook.

| Resource | Link |
|---|---|
| **Download (GitHub)** | [github.com/vfaner/jync/releases/latest](https://github.com/vfaner/jync/releases/latest) |
| **Download (Gitee)** | [gitee.com/super_rgh/jync/releases/latest](https://gitee.com/super_rgh/jync/releases/latest) |
| **Mirror (AtomGit)** | [gitcode.com/vfaner/jync](https://gitcode.com/vfaner/jync) |
| **Website** | [jync.qqmu.com](https://jync.qqmu.com) |
| **Video walkthrough** | [Bilibili · 14 minutes, from connection to real-time sync](https://www.bilibili.com/video/BV1iHYJ6vEEd) |
| **Docs** | [User Guide](https://jync.qqmu.com/docs/jync-user-guide.pdf) · [Operations Guide](https://jync.qqmu.com/docs/jync-ops-guide.pdf) · [Overview](https://jync.qqmu.com/docs/jync-overview.pdf) |

> AtomGit (gitcode.com) is a synced mirror of the code and release assets; downloads, issues and pull requests are handled on GitHub / Gitee.

---

## Contents

- [Background](#background)
- [Capabilities](#features)
- [Screens](#screenshots)
- [Sync engine: full load, incremental and log CDC](#engine)
- [Performance tuning](#performance)
- [Scheduling](#schedule)
- [Column cleansing](#cleansing)
- [Supported databases](#databases)
- [Quick start](#deploy)
- [Common settings](#config)
- [Sign-in, roles and SSO](#auth)
- [Workflow](#workflow)
- [AI-assisted conversion (optional)](#ai)
- [Comparison with existing tools](#comparison)
- [Architecture and stack](#architecture)
- [Docs and tests](#docs)
- [Contributing](#contributing)
- [License](#license)

---

<a name="background"></a>
## Background

When a public-sector or enterprise system moves from MySQL, Oracle or SQL Server to a domestic database such as DM (达梦), Kingbase or OceanBase, the migration rarely ends when the data is copied. Old and new systems run side by side, cutover happens in batches, and read-only archives coexist with live services — all of which require the two databases to **stay consistent for weeks or months**.

Existing tools always leave one gap. Commercial sync products are licensed per node, with long procurement cycles and high cost. Log-based tools such as Debezium and Flink CDC are capable, but require Kafka, Flink and job orchestration before the first row moves. DataX and Kettle excel at one-off transfers, but do not own long-running incremental sync, structure synchronization or resume-from-checkpoint.

**Jync turns that logic into a product**: one jar, a browser for connections, tables, scheduling and start/stop, and no scripts or message queues to operate. It is open source under MIT, auditable, and fully self-hostable.

**Design goals**

- **One jar**: metadata store, scheduler and web UI included — run it on an isolated network.
- **Minutes to first sync**: connections can be tested before saving, objects are checkboxes, and every setting has a default.
- **Structure is a first-class citizen**: DDL, indexes, views, procedures and type mapping are the tool's job.
- **Domestic databases first**: DM, Kingbase, OceanBase, TiDB and GaussDB have dedicated dialects.
- **Observable**: rows, elapsed time, throughput and bottleneck shares are written to the log and shown in the UI, and faults can be pushed out.

---

<a name="features"></a>
## Capabilities

| Area | What Jync does |
|---|---|
| Full load | Keyset chunking + in-table parallelism + streamed reads + per-chunk checkpoints; INSERT-first into empty targets, deferred secondary indexes; hundreds of thousands of rows/s |
| Incremental sync | Timestamp, row SCN, identity column or full comparison, selected automatically; one cursor-range query; safety-lagged watermark; cursor advances only after commit |
| Log CDC | MySQL binlog, Oracle LogMiner, DM LogMiner, PostgreSQL-family logical decoding (including openGauss and other domestic kernels), SQL Server CDC, YashanDB YStream; long-lived streaming replay with second-level latency |
| Structure sync | Tables, indexes, views, procedures and functions; type mapping, function conversion, identifier quoting, pagination and DDL overrides |
| ETL | Copy result rows from a customer SQL query; 7 analytic engines; multi-statement scripts; overwrite / append / range overwrite; resumable runs; tables sharing a source and target can form a **batch** with one connection pair and one schedule |
| Dedicated migration | Three whole-object copies: Elasticsearch / OpenSearch → Easysearch (index discovery, settings / mappings / alias copying, bulk writes preserving `_id` / `_routing`, compatibility probe); MongoDB → MongoDB (collection options and indexes, documents copied verbatim with `_id` preserved); MongoDB → KingbaseES (columns inferred from a sample, `_id` primary key, batched INSERT / `ON CONFLICT` upsert). All with a precheck, run manually or on a cron, cancellable, with run history |
| Data shaping | Column cleansing (7 rule types), table-name mapping, read-only data preview, on-demand row-count audit, task cloning, bulk task creation (binned by table size) |
| Alerting | Webhook pushes for sync failures, stuck polling, projects not running, CDC stalls, data drift, unhealthy connections and PG slot backlog; generic JSON / DingTalk / WeCom / Feishu; cooldown and recovery notices; encrypted address storage |
| Reliability | Idempotent upsert, checkpoint resume, database leases, at-least-once delivery, automatic catch-up after downtime |
| Observability | Dashboard, change log, audit log, system log, per-table throughput log and CDC status panel, on-demand row-count audit, database and task performance charts (7-day history), optional Prometheus scrape endpoint (Bearer token, off by default) |
| Delivery | jar / Docker / Kubernetes; Chinese and English UI, light and dark themes; responsive layout usable on phones; local accounts plus three SSO modes |

> **ETL batches:** each table stays its own ETL task, keeping its extraction SQL, target table, write mode, checkpoint and run history. A batch only puts tables that share a source and target under one schedule: a run opens a single source/target connection pair, executes the members in order, and keeps going when one member fails. Source and target are locked once the batch is created, and members no longer carry a schedule of their own; tick tables from the source catalog to generate `select *` members and rewrite each SQL afterwards.
>
> **Alerting only reads state:** the evaluator reads status Jync already keeps and never changes how a sync, CDC stream or ETL task runs. When disabled it evaluates no rules and sends nothing.

---

<a name="screenshots"></a>
## Screens

<table>
  <tr>
    <td align="center" width="33%"><a href="https://jync.qqmu.com/images/jync_kanban.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_kanban.webp" alt="Kanban" width="100%"></a><br><sub><b>Kanban</b> · resources, tasks and a 24h trend</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_xiangmu.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_xiangmu.webp" alt="Sync tasks" width="100%"></a><br><sub><b>Sync tasks</b> · state, last sync and details</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_db.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_db.webp" alt="Connections" width="100%"></a><br><sub><b>Connections</b> · source and target side by side</sub></td>
  </tr>
  <tr>
    <td align="center" width="33%"><a href="https://jync.qqmu.com/images/jync_cdc.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_cdc.webp" alt="Log CDC" width="100%"></a><br><sub><b>Log CDC</b> · position, last event, throughput, skips</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_xiangmu_xn.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_xiangmu_xn.webp" alt="Task performance" width="100%"></a><br><sub><b>Task performance</b> · rows, latency, failures and change mix</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_lqx.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_lqx.webp" alt="Column cleansing" width="100%"></a><br><sub><b>Column cleansing</b> · per table and column, before write</sub></td>
  </tr>
  <tr>
    <td align="center" width="33%"><a href="https://jync.qqmu.com/images/jync_warn.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_warn.webp" alt="Alerting" width="100%"></a><br><sub><b>Alerting</b> · webhook, format, categories and cooldown</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_xiangmu_suoyin_ai.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_xiangmu_suoyin_ai.webp" alt="AI draft" width="100%"></a><br><sub><b>AI draft</b> · candidate SQL streams into the editor</sub></td>
    <td align="center"><a href="https://jync.qqmu.com/images/jync_kanban_anye.webp" target="_blank" rel="noopener"><img src="https://jync.qqmu.com/images/jync_kanban_anye.webp" alt="Dark theme" width="100%"></a><br><sub><b>Dark theme</b> · the whole palette switches together</sub></td>
  </tr>
</table>

> Screenshots are hosted on the website; the README keeps nine. The rest (ETL, batch create, audit, system logs, English UI …) are on the [features page](https://jync.qqmu.com/features.html).

---
<a name="engine"></a>
## Sync engine: full load, incremental and log CDC

There are three data paths: **how the first copy moves**, **how changes are followed afterwards**, and **how to reach second-level latency**.

```
First full load:  keyset chunks → N workers in parallel → streamed reads → per-batch commit → checkpoints
Incremental:      cursor > last value AND cursor <= watermark → batched upsert → advance cursor
Log CDC:          record position → chunked baseline → long-lived replay → advance position after commit
```

### First full load: chunked, parallel, streamed

A full load is not "SELECT the whole table, then INSERT in batches"; it is a bounded, interruptible, resumable pipeline:

```
source cursor (fetch-size streaming)
  → row mapping / column cleansing (in memory, row by row)
  → JDBC batch (bounded by rows and bytes)
  → target commit (every batch)
  → checkpoint (every chunk)
```

- **Streamed reads**: the result set is fetched in `full-load-fetch-size` increments and mapped, cleansed and bound row by row. Heap holds only the current batch and the current LOB, not the whole table.
- **Keyset chunks**: tables with a primary key are paged by key, `chunk-size` defaults to 100,000 rows per chunk; every chunk commits and checkpoints on its own, so a crash re-reads at most one chunk.
- **In-table parallelism**: a single-column integer key is split arithmetically into disjoint `[MIN, MAX]` ranges; text, UUID and composite keys sample boundaries in the source key order; each worker takes its own connection, up to 8. Disjoint ranges plus idempotent writes make completion order irrelevant.
- **Keyless tables**: when the target is empty and some column can order the rows, OFFSET paging resumes from the target's row count; otherwise the table stays a single streamed read.
- **Write-side strategy**: an empty target takes plain `INSERT` instead of upsert; non-unique secondary indexes are built after the load; target foreign keys can be suspended during the load.
- **When it runs**: only on first start, "reset progress" or a CDC "rebuild baseline". Daily polling syncs increments only.

### Incremental polling: read the changes only

Each table issues one query per cycle:

```sql
SELECT ... FROM t WHERE cursor > :last AND cursor <= :watermark
```

- Cursor columns are detected automatically: `TIMESTAMP` sees inserts and updates, `ROW_SCN` sees Oracle row-level changes, `IDENTITY` sees inserts only. Small keyed tables can opt into periodic `FULL_COMPARE`; deletes are handled only when "sync row deletes" is enabled, and larger tables can use chunked key comparison or log CDC.
- **Index the cursor column**: polling depends on it, and without an index every cycle scans the whole table; the project page flags tables whose cursor column has no index.
- Watermark lag `safety-lag-ms`: late-committing transactions are read again next cycle and absorbed by the idempotent upsert.
- **The cursor advances only after the target commits**, so failure recovery is at-least-once and rows are not lost.
- The default polling mode never reads the source's binlog and creates no triggers — one read-only account is enough.

### Log CDC: second-level streaming replay

Six source families can switch to log CDC per task and see `INSERT / UPDATE / DELETE` directly from the source log:

| Engine | Source prerequisites (checked item by item) | Position |
|---|---|---|
| MySQL binlog | 5.7+; `log_bin=ON`, `binlog_format=ROW`, `binlog_row_image=FULL`; account with `REPLICATION SLAVE` and `REPLICATION CLIENT`; binlog retention ≥ 24h | binlog file + offset (with GTID) |
| Oracle LogMiner | 11g+; `ARCHIVELOG`; database-level `SUPPLEMENTAL LOG DATA`; table-level `SUPPLEMENTAL LOG DATA (ALL) COLUMNS`; mining privileges; archive retention ≥ 24h | SCN |
| DM LogMiner | DM8; `ARCH_MODE=Y`; `RLOG_APPEND_LOGIC=2` (redo carries full old images); `V$ARCHIVED_LOG` readable and `DBMS_LOGMNR` executable; only archived files can be mined, so the archive switch interval is the latency floor (schedule `ALTER SYSTEM SWITCH LOGFILE`) | SCN (`MAX(NEXT_CHANGE#)`) |
| PostgreSQL logical decoding | PostgreSQL 11+ and PG-family domestic kernels (openGauss, GaussDB, Kingbase, Highgo, Vastbase, Oscar, RaseSQL, SeaBoxSQL); `wal_level=logical`; account with `REPLICATION` (openGauss does not require it); table `REPLICA IDENTITY` not `NOTHING`; openGauss should set `enable_slot_log=on` | LSN |
| SQL Server CDC | 2012+; `sp_cdc_enable_db`; SQL Server Agent running; account in `cdc_Admin` / `cdc_reader` | LSN |
| YashanDB YStream | YashanDB 23.4+; `ARCHIVELOG` and connected to the primary; account with `CREATE SESSION`, `SELECT ANY TABLE`, `YSTREAM_CAPTURE`, `SELECT ANY DICTIONARY`; run `ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS` for **every selected table**; tables must be selected explicitly; at most 32 YStream services per instance | five-part tuple (`commitScn` / instance / `groupLsn` / `groupOffset` / row) |

- **YashanDB goes through YStream, not LogMiner**: 23.4.7.100 has no `DBMS_LOGMNR`; its logical log engine is YStream (`DBMS_YSTREAM_ADM`), used by the "YStream real-time log" strategy without triggers or table changes. DML on a table lacking supplemental logging **silently never enters the stream**, so both the probe and startup check every table and print the exact ALTER statement.
- **One flow**: record the position → chunked baseline → long-lived replay. Oracle and DM share the LogMiner pipeline; only the position query, mineable files and invocation go through their own dialect layer.
- **Low idle latency**: with data present the next round starts immediately; only a truly idle stream sleeps (LogMiner / SQL Server 1s, PostgreSQL 500ms, minimum 50ms). MySQL binlog is pushed by the server.
- **Multi-instance takeover**: a database lease guards each CDC stream; a failed holder is replaced within 60s.
- **Consistency contract**: commit the target, re-check the lease, then advance the position. A crash replays from the last position; the overlap is absorbed by the idempotent upsert.

### Speed and scale

On a single-host container benchmark (500k-row Oracle table), full load measured **216k rows/s**; INSERT-first into an empty target reached 211k rows/s. Log CDC end-to-end latency is **second-level**, with continuous replay while data is present. For the common migration scale of dozens of tables and millions of rows, Jync's latency and throughput are in the same order as Flink CDC; the difference is that the deployment surface is one jar.

---

<a name="performance"></a>
## Performance tuning

The full load prints one throughput line per table per worker, splitting "slow" into three actionable shares:

```text
table=orders thread=full-load-orders rows=500000 changed=500000 elapsed=2.3s rows/s=216000.0 read=38% write=52% commit=10% batches=250 rowsPerBatch=2000
```

High `read%` points at the source cursor or network; high `write%` at target constraints, indexes or LOBs; high `commit%` at redo or disk latency — raise the batch first. Four settings cover most cases:

| Setting | Default | Suggested | What it does |
|---|---|---|---|
| `sync.full-load-batch-size` | `2000` | 1000–5000 | Rows per batch; larger means fewer commits, higher rollback and memory cost |
| `sync.full-load-fetch-size` | `2000` | 1000–5000 | Source result-set batch; lower it for wide LOB tables |
| `sync.full-load-parallelism` | `2` | 1–4 | Workers per table, bounded by the pool; 1→2 gives the largest gain |
| `sync.chunk-size` | `100000` | 50k–200k | Rows per chunk, and the most a crash can re-read |

Measured on one container with a 500k-row Oracle table: empty-table `INSERT` ≈ 211k rows/s (`MERGE` ≈ 84k); parallelism 1 / 2 / 4 gives 117k / 184k / 216k rows/s; deferring secondary indexes is about 47% faster. The numbers show direction only — disk, network, row width and concurrent load decide the rest.

> Full log-field reference, bottleneck table, pool constraints and a step-by-step procedure: [Operations Guide (PDF)](https://jync.qqmu.com/docs/jync-ops-guide.pdf). Performance curves and sampling: [User Guide (PDF)](https://jync.qqmu.com/docs/jync-user-guide.pdf).

---

<a name="schedule"></a>
## Scheduling

Scheduling decides how often increments are followed. It has three layers: global default, task override, and the long-lived CDC consumer.

- **Global default** `sync.poll-interval` (2000 ms); the task page can set its own interval or Cron expression, and a blank field inherits the global value. Schedules register when a task starts, so a running task must be stopped before changing them.
- **Polling interval**: the effective range is **200ms – 24h**. Missed interval fires are dropped — ten queued 2-second polls are meaningless when the next one sees every change anyway.
- **Cron**: Quartz syntax, **6 or 7 fields, seconds first**, taking precedence over the interval; validated on save and on start. Evaluated in the server time zone — pin `-Duser.timezone` or the container `TZ`. A missed Cron fire runs once when a thread is free.

```text
0/2 * * * * ?        every 2 seconds
0 0/5 * * * ?        every 5 minutes
0 30 2 * * ?         daily at 02:30
0 0 8 ? * MON-FRI    weekdays at 08:00
```

- **Run once on start**: a started task runs immediately and then follows its schedule.
- **Sync now**: runs one catch-up round from the last successful position without changing the enabled state; clicking during a run merges into one catch-up.
- **Stop**: cancels the schedule and lets the running round exit at the next committed batch boundary, then releases the lock.
- **CDC tasks have no Quartz schedule**: the long-lived consumer replays continuously, the schedule card is hidden, and idle mining intervals are configured per engine.
- **Multi-instance**: Quartz uses in-memory storage; nodes rebuild schedules from the enabled flags in the database at startup, a reconciler re-aligns every 30 seconds, and a database lease serializes actual execution.

---

<a name="cleansing"></a>
## Column cleansing

Fixed rules apply to string columns **before rows are written to the target**; the source data is never modified.

| Rule | Effect | Example |
|---|---|---|
| `TRIM` | Trim leading and trailing Unicode whitespace | `"  张三 "` → `"张三"` |
| `UPPER` | Uppercase | `"ab-01"` → `"AB-01"` |
| `LOWER` | Lowercase | `"AB-01"` → `"ab-01"` |
| `BLANK_TO_NULL` | Empty or whitespace-only string to `NULL` | `"   "` → `NULL` |
| `STRIP_PREFIX` | Remove one fixed prefix | prefix `CN-`: `"CN-001"` → `"001"` |
| `STRIP_SUFFIX` | Remove one fixed suffix | suffix `.csv`: `"a.csv"` → `"a"` |
| `REGEX_REPLACE` | Replace all regex matches | `\s+` → space; `^(\d{4})-` → `$1` |

- Rules are configured per table and column, table and column names are **case-insensitive**, and within a table they run in saved order (`TRIM` then `BLANK_TO_NULL` turns whitespace into `NULL`).
- **Only string values are touched**; numbers and dates pass through. Full load, polling and CDC replay share the same rules.
- A cleansed column is materialized in full; a single value above `sync.lob.force-inline-max-bytes` (default 1MB) fails with the table, column and setting named.
- `STRIP_PREFIX` / `STRIP_SUFFIX` / `REGEX_REPLACE` require a parameter; expressions and replacements are validated with Java semantics at save time.

---

<a name="databases"></a>
## Supported databases

**34 built-in types**, 25 of them with bundled drivers. The map below is from the website: sources on the left, the two engines in the middle, targets on the right; each chip carries the modes it supports. Click it for the full reference.

[![Database capability map: sources, sync engines and targets](docs/images/jync_dbmaps.webp)](https://jync.qqmu.com/database.html)

- **Custom types**: JDBC URL, driver class and driver jar path, loaded at runtime in an isolated `URLClassLoader`; pick a dialect family (generic / Oracle / MySQL / PostgreSQL / SQL Server / DB2 / SAP HANA).
- **Bundled drivers**: everything except **GBase, Oscar, Huawei GaussDB, SAP HANA, ClickHouse, Hive, Impala, Elasticsearch and MongoDB (Atlas SQL)** ships with the jar. Those vendors publish no redistributable artifact, so supply the jar and path; the driver class can be overridden on the same card.
- **Protocol-compatible presets**: GreatDB, UPDRDB and KunDB speak the MySQL protocol; RASESQL and SeaboxSQL speak the PostgreSQL protocol, reusing bundled drivers and dialect families.
- **Analytic / search engines**: Doris, ClickHouse, Hive, Impala, Elasticsearch and MongoDB have no log-based CDC, so they are **real-time sync sources only** (polling / full compare) and **never targets**; in ETL all but Elasticsearch and MongoDB can be written. MongoDB has two paths: the Atlas SQL JDBC preset and the bundled native driver.
- **Dedicated migration is not JDBC**: Elasticsearch/OpenSearch → Easysearch, MongoDB → MongoDB and MongoDB → KingbaseES use dedicated connections and tasks, outside the capability matrix above; credentials live in encrypted fields only, and no password is allowed inside a MongoDB URI or Kingbase JDBC URL. See [Known limitations](docs/known-limitations.md#11-专属迁移esopensearch--easysearchmongodb--mongodb--kingbasees).
- The GaussDB vs openGauss distinction, TDSQL / PolarDB editions and HANA's CDC limits are covered on the [website's database page](https://jync.qqmu.com/database.html); driver versions and licences are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

<a name="deploy"></a>
## Quick start

### Requirements

| Item | Requirement |
|---|---|
| JDK | **17+** |
| Port | `8080` by default |
| Memory / disk | Heap ≥ 512 MB recommended, 1 GB free; LOB sync scales with the largest value |
| Working directory | Fixed: `./data` (metadata store and key) and `./logs` are created relative to it |

The first start creates `./data` and `./logs` in the working directory. **Do not start from different directories** — each one gets its own empty metadata store, and the saved configuration looks lost.

### Get and run

- GitHub: <https://github.com/vfaner/jync/releases/latest>
- Gitee: <https://gitee.com/super_rgh/jync/releases/latest>
- From source: `mvn clean package -DskipTests`, output `target/jync.jar`

```bash
mkdir -p /opt/jync && cd /opt/jync
java -Xms512m -Xmx2g -Duser.timezone=Asia/Shanghai \
  -jar jync.jar --spring.config.additional-location=file:./application.yml
```

> ⚠️ Use `--spring.config.additional-location` (layered override). The replacing form `spring.config.location` drops the jar's built-in settings — the login page then shows `??login.title_en_US??` and a fresh deployment creates no tables.
>
> 🕐 `-Duser.timezone` also decides how Cron expressions are evaluated. Set it explicitly when the host defaults to UTC.

Minimal `application.yml`:

```yaml
server:
  port: 8080
sync:
  poll-interval: 2000
  full-load-parallelism: 2
  max-pool-size: 5
  crypto-password: ""   # empty = generated on first start, stored in data/<db>.key
  crypto-salt: ""
```

### First sign-in

Open `http://<server>:8080`:

| User | Role | Initial password |
|---|---|---|
| `admin` | Administrator | `123456` |
| `view` | Viewer | `123456` |

**Change the password immediately after the first sign-in**; until then only the change-password page is reachable. Passwords are stored with BCrypt.

### Production deployment

Production settings, an external metadata store, systemd, Docker / Kubernetes, reverse proxy and HTTPS, upgrade/backup and air-gapped installation are covered in:

- [Operations Guide (PDF)](https://jync.qqmu.com/docs/jync-ops-guide.pdf)
- The website's [deployment page](https://jync.qqmu.com/deploy.html)
- [Container deployment notes](docs/deploy-container.md) in this repository

> 🔐 Back up `./data` (including `<db>.key`), `./logs` and `application.yml` before upgrading. Without the key, saved connection passwords, AI keys and alert URLs cannot be decrypted and must be re-entered.

---

<a name="config"></a>
## Common settings

The most-used `sync.*` keys in `application.yml` (full reference: [User Guide (PDF)](https://jync.qqmu.com/docs/jync-user-guide.pdf)):

| Key | Default | Meaning |
|---|---|---|
| `sync.poll-interval` | `2000` | Global poll interval (ms) |
| `sync.batch-size` | `500` | JDBC batch rows for incremental polling and CDC replay |
| `sync.fetch-size` | `1000` | Incremental result-set fetch size |
| `sync.full-load-batch-size` | `2000` | Rows per batch during the first full load |
| `sync.full-load-parallelism` | `2` | Full-load workers per table, bounded by the pool |
| `sync.chunk-size` | `100000` | Rows per full-load chunk; `0` disables chunking |
| `sync.max-pool-size` | `5` | Connection-pool ceiling per database |
| `sync.crypto-password` / `crypto-salt` | generated when empty | Encryption keys for credentials and alert URLs |
| `sync.ai.enabled` | `true` | `false` removes the AI menu and endpoint |
| `app.update-check.enabled` | `true` | New-version check; `false` makes no outbound request |
| `metrics.enabled` | `true` | Sampling master switch; does not affect the sync path |

> Connection passwords, AI keys and alert webhook URLs are stored with AES-256 encryption and an `enc:` prefix that prevents double encryption. When the key is left empty, the first start generates it into `data/<db>.key`.

---

<a name="auth"></a>
## Sign-in, roles and SSO

| User | Role | Initial password | Permissions |
|---|---|---|---|
| `admin` | Administrator | `123456` | Everything |
| `view` | Viewer | `123456` | Read-only |

- **One permission rule**: every POST requires an administrator. Viewers see every page and all data, but have no write buttons and crafted requests are rejected.
- **Initial accounts**: written on first start; skipped when the user table is not empty, so nobody's password is reset.
- **Forgotten password**: not recoverable; delete the `app_user` row and restart to seed it again.

**SSO** (Configuration → External authentication) supports three modes, one active at a time:

| Mode | Use case | Deployment requirement |
|---|---|---|
| Credentials | The portal exposes a login API | Jync can reach it; form or JSON request with field mapping |
| Gateway header | The gateway/reverse proxy has signed the user in and injected identity headers | Jync sits behind the gateway; only direct peers in `app.security.trusted-proxies` are trusted |
| Ticket | A login centre issues a one-time ticket | Configure the validation URL; only http(s), redirects not followed |

- External users keep a session only; they are not written to `app_user`, have no local password and no change-password entry.
- Local accounts remain as the emergency entrance `/login/local`. Enable and configure external authentication first; once local login is closed, `POST /login` is rejected too.
- **Restore local login**: start once with `JYN_RESET_LOGIN=true` (same as `app.login.reset-on-startup: true`); Jync re-enables and saves local login. Fix the external configuration, remove the variable and set it back to `false`.
- Embedding in an iframe needs HTTPS plus `JYN_COOKIE_SECURE=true` and `JYN_COOKIE_SAME_SITE=None`.

---

<a name="workflow"></a>
## Workflow

1. **Connections** → create source / target connections → "Test connection" before saving.
2. **Real-time sync → New task** and choose the source and target.
3. **Task detail** → tick tables / views / procedures; type a different target table name on the right (blank keeps the name).
4. Configure **capture strategy, schedule, full-load performance and column cleansing** as needed.
5. "**Sync now**" for one verification run, or "**Start automatic sync**" for continuous increments.
6. Read each round in the **change log** and the overall state on the **dashboard**; use **Row-count audit** on the task page when you need to confirm both sides match.

**ETL**: **Sync tasks → ETL tasks**, then create a task or a batch → extraction SQL and target table → write mode and schedule → start. Multiple tables that share one source and target belong in one batch.

**Dedicated migration**: **Sync tasks → Dedicated migration** → manage connections → pick a migration type → choose objects, target naming and write mode → run the precheck → save, then "Run now" or enable a cron. All three types copy the data as it stood when the run started rather than syncing continuously; rerunning OVERWRITE makes the target converge.

- **ES / OpenSearch → Easysearch**: both sides are search connections; choose indices and copy settings / mappings / aliases and documents.
- **MongoDB → MongoDB**: both sides are MongoDB connections (`databaseName` selects the database); choose collections and copy collection options, indexes and documents with `_id` unchanged. Copying between two databases on the same cluster needs two connection records, because a task requires its source and target connections to differ.
- **MongoDB → KingbaseES**: the source is MongoDB and the target is a KingbaseES connection (`schemaName`, blank for the current schema); choose collections. The table is created from sampled columns, `_id` is the primary key, and with `upsertOnId` on, an existing `_id` is overwritten.

**Alerting**: **Configuration → Alert notifications** → Webhook URL, message format, categories and repeat interval → save, then send a test message.

> When a target table already holds some of the rows on the first full load, an entry reads `50 row(s) inserted (53 scanned, 3 already up to date)` — rows scanned, rows written and rows already current at a glance.

---

<a name="ai"></a>
## AI-assisted conversion (optional)

Procedural control flow cannot be translated reliably by text replacement. **AI settings** offer another route: the model **drafts a candidate** and a person reviews it before it is stored.

- **Four protocols**: OpenAI-compatible, Anthropic, Ollama (OpenAI-compatible endpoint), Ollama (native `/api/chat`).
- **Hard boundary**: AI only drafts; it **never takes part in synchronization**. A candidate enters a DDL override only after human approval, and no provider means no outbound request.
- **Real probe**: saving sends one real request with `max_tokens: 1`, not a TCP probe.
- **Review page**: source definition and mechanical conversion side by side; the model's own "uncertain" notes are worth reading before the SQL.
- **Thinking levels**: follow the model, off, low, high; Anthropic gets `thinking.budget_tokens`, OpenAI-shaped providers get `reasoning_effort`.

> ⚠️ Conversion sends procedure source to the endpoint you configure. Use a local inference endpoint on isolated or regulated networks (the Ollama presets work out of the box).

---

<a name="comparison"></a>
## Comparison with existing tools

> Scope: as of 2026-10, based on each tool's official documentation and open-source default deployment, and only the "out-of-the-box continuous heterogeneous sync" overlap. Each tool is stronger in its own place; this table is not an overall judgement. A fuller dimension-by-dimension comparison is at <https://jync.qqmu.com/compare.html>.

| Dimension | **Jync** | Debezium + Kafka | DataX | Kettle | Navicat / DBeaver transfer |
|---|---|---|---|---|---|
| Deployment | **One jar** | Kafka + Connect + ZK | Client scripts | Desktop + repository | Desktop client |
| Configuration | **Web UI** | YAML / REST + consumer code | JSON jobs | Graphical ETL | Wizards |
| Time to first sync | **Minutes** | Days: Kafka stack first | Hours: hand-written JSON | Days: transformation and job model | Minutes, but one-off only |
| Continuous incremental | Polling / Cron + log CDC (MySQL, Oracle, DM, PostgreSQL, SQL Server, YashanDB); six analytic engines as polling sources | Log-based | Not applicable | Partial (self-scheduled) | Partial (scheduled tasks / full compare) |
| Full load | **Keyset chunks + in-table parallelism + streamed reads + checkpoints** | Initial snapshot; heterogeneous sinks need custom consumers | Sharded jobs | Inside a job | One-off wizard |
| Structure sync | **Tables / indexes / views / procedures created automatically** | Partial; sink code required | Pre-create tables | Partial | One-off |
| ETL and batches | **Extraction SQL + 7 analytic engines + batches sharing connections and a schedule** | External tooling | Sharded jobs | Graphical transforms | Import/export only |
| Domestic databases | **DM / Kingbase / OceanBase and 10+ more as first-class citizens** | Partial | Partial | Partial | Partial |
| Source intrusion | **Read-only by default**; CDC needs replication rights but stays read-only | binlog / WAL required | Read-only | Read-only | Read-only |
| Resume / idempotency | **Persistent cursor + keyed upsert + checkpoints** | Offsets | Not applicable | Partial | Not applicable |
| Alerting and observability | **Seven fault categories over Webhook, dashboard, change / audit / system logs, Prometheus scrape endpoint** | External monitoring stack | None | Logs | Client notifications |
| Operating cost | **One process** | Streaming platform + consumers | External scheduler | Repository + scheduler | Client tasks / manual |

**Main differences**

1. **One jar against a streaming stack**: when scale does not justify the operating cost, the gap decides.
2. **Minutes to first sync**: implementation staff follow a wizard, without first learning a streaming platform.
3. **Structure travels with data**: DDL, type mapping, indexes and procedure conversion stop being your job.
4. **Built for domestic databases and legacy-system migration**: dedicated dialects, not "generic JDBC might connect".
5. **Full load designed for continuous sync**: chunking, parallelism, streaming and checkpoints make the first copy a resumable, observable process.

---

<a name="architecture"></a>
## Architecture and stack

**Stack**: Java 17 · Spring Boot 2.7 · Spring Data JPA / Hibernate · Quartz · H2 / MySQL / PostgreSQL metadata store · Thymeleaf server-side rendering · vanilla JavaScript (no CDN, no front-end build).

```text
com.qqmu.jync
├── config           i18n, Quartz, Jackson, SecurityConfig, SyncProperties
├── controller       MVC; controller/api exposes REST endpoints
├── service
│   ├── connection   DataSourceManager, DriverLoader, connection tests
│   ├── metadata     dialect metadata readers + snapshots
│   ├── monitor      ChangeDetector, CursorStrategyResolver
│   ├── converter    SqlDialect, type mapping, SQL body conversion
│   ├── sync         SyncEngine, DataSyncService, StructureSyncService, DdlExecutor
│   ├── cdc          binlog / LogMiner / PG / SQL Server / YStream consumers
│   ├── etl          extraction, write modes, checkpoints, batches
│   ├── alert        fault collection, evaluation, Webhook delivery
│   ├── task         Quartz scheduling, three-layer locks, context assembly, startup recovery
│   ├── lob          streamed LOB reads, binding and spilling
│   └── ai           provider settings, shared HTTP layer, draft and review
├── model / repository / dto / util
```

- **Three-layer lock**: Quartz `@DisallowConcurrentExecution` → per-task JVM `ReentrantLock` → database lease (across restarts and instances).
- **Transactions**: cursor advancement and lock acquisition use `REQUIRES_NEW` so they do not roll back with data writes.
- **Schedule reconciliation**: in multi-instance deployments every node aligns its local Quartz schedules with the enabled flags in the shared store.
- **Alert state**: fault lifecycles live in the database, so nodes and failovers share one clock.

---

<a name="docs"></a>
## Docs and tests

- **Container deployment and operations**: [docs/deploy-container.md](docs/deploy-container.md) (environment variables, TLS, multi-instance, upgrades and troubleshooting)
- **Known limitations**: [docs/known-limitations.md](docs/known-limitations.md) (CDC boundaries, keyless tables, LOBs, procedure conversion)
- **Oracle → DM migration walkthrough**: [docs/migration-harness-oracle-to-dm.md](docs/migration-harness-oracle-to-dm.md)
- **Project documents (PDF)**: [Product overview](https://jync.qqmu.com/docs/jync-overview.pdf) · [User guide](https://jync.qqmu.com/docs/jync-user-guide.pdf) · [Deployment and operations](https://jync.qqmu.com/docs/jync-ops-guide.pdf)
- **Release history**: [GitHub Releases](https://github.com/vfaner/jync/releases) · [Gitee Releases](https://gitee.com/super_rgh/jync/releases) · [AtomGit mirror](https://gitcode.com/vfaner/jync/releases)
- **Third-party driver licenses**: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)

```bash
mvn test
```

**4,433 tests: 4,432 pass, and one Linux-only key-file permission case is skipped on macOS by environment.**

Coverage highlights:

- **Dialects and SQL**: idempotent upserts per dialect, type limits, SQL rewriting that never touches literals or comments.
- **Full load and resume**: keyset chunks, disjoint parallel ranges, keyless OFFSET resume, checkpoints, cleansing and key boundaries.
- **Security**: password, AI-key and alert-URL encryption, viewer POST rejection, CSRF rejection, SSO mode isolation.
- **CDC**: prerequisite probes for six engines, position discipline, lease exclusion, failure summaries.
- **ETL and alerting**: shared connections and sequential batch runs, write modes and checkpoints, alert categories, cooldown and recovery notices.
- **Pages and i18n**: every conditional branch renders without `??`, no Chinese leaks in the English UI, bundle key parity.
- **Upgrades**: GitHub → Gitee fallback, download verification, replacement backup and rollback.

Real-database integration tests (`*IT`, excluded from the default suite, require Docker): Oracle 23ai parallel full load with `ROWDEPENDENCIES`; MySQL 8 binlog baseline → streaming, crash rerun, 100k-row consistency, purge and disconnect faults. Run with `-Dapi.version=1.44` (Docker Engine ≥ 29).

---

<a name="contributing"></a>
## Contributing

| Channel | Account |
|------|------|
| QQ | [817094](https://wpa.qq.com/msgrd?v=3&uin=817094&site=qq&menu=yes) |
| QQ | [2912167928](https://wpa.qq.com/msgrd?v=3&uin=2912167928&site=qq&menu=yes) |
| QQ group | [426669837 · 沐沐开源工坊](https://qun.qq.com/universal-share/share?ac=1&authKey=xm%2BfgdraFGDpH8GHkV4DbuMjArg6BsPdNnXKUqcWvFXI1ocS56TcioSRYOVBvn5Q&busi_data=eyJncm91cENvZGUiOiI0MjY2Njk4MzciLCJ0b2tlbiI6Ind0V1pMYlVieXJkQmtnMVgveUFsOHFnbFhTd28wUE03dy9uVUErVExBdnlkMENnMis0UmhqWlJjcURzRHFnUEMiLCJ1aW4iOiI4MTcwOTQifQ%3D%3D&data=tZVIfZoO6N9QWSJE6ZbdZR15OmuLpSVucid5TicWYaMgb9Go1B975NZk0iZOo69AEzXQ4XucFtenImLltzFI5w&svctype=4&tempid=h5_group_info) |
| WeChat | qqmu66 |
| Email | [vfaner@88.com](mailto:vfaner@88.com) |
| Website | <https://jync.qqmu.com> |

Issues, feature requests and pull requests are welcome through any channel above. If Jync helps you, a **Star** ⭐ is appreciated.

---

<a name="license"></a>
## License

[MIT License](LICENSE), for commercial and non-commercial use. Bundled third-party JDBC drivers keep their vendors' licenses; see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). GBase, Oscar and Huawei GaussDB drivers are not redistributed with this project.

Maintained by 沐沐开源工坊: GitHub [@vfaner](https://github.com/vfaner), Gitee [@super_rgh](https://gitee.com/super_rgh), AtomGit [@vfaner](https://gitcode.com/vfaner). Use, modify and redistribute freely (MIT); the one request is not to replace the "Jync / 捷同" name, logo and author attribution with your own — see [TRADEMARK.md](TRADEMARK.md).
