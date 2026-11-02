# Jync · Heterogeneous Database Real-Time Sync

> **Jync**, pronounced "sync" — **J** for Java; the Chinese name 捷同 means "swift and identical".
> A single-JAR database synchronization engine: **chunked parallel full load, read-only incremental capture, second-level log CDC**, plus SQL-driven ETL and Webhook alerting.

[简体中文](README.md) | **English**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen.svg)](https://spring.io/projects/spring-boot)

![Jync · Heterogeneous Database Real-Time Sync](docs/images/jync.png)

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

> AtomGit (gitcode.com) is a synced mirror of the code and release assets; downloads, issues and pull requests are handled on GitHub / Gitee.

---

## Contents

- [Background](#background)
- [Capabilities](#features)
- [Screens](#screenshots)
- [Sync engine: full load, incremental and log CDC](#engine)
- [Full-load performance and tuning](#performance)
- [Scheduling](#schedule)
- [Column cleansing](#cleansing)
- [Supported databases](#databases)
- [Deployment](#deploy)
- [Configuration reference](#config)
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
    <td align="center" width="33%"><a href="docs/images/jync_kanban.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban.png" alt="Dashboard" width="100%"></a><br><sub><b>Dashboard</b> · resources, tasks and 24h trends</sub></td>
    <td align="center" width="33%"><a href="docs/images/jync_xiangmu.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu.png" alt="Real-time sync tasks" width="100%"></a><br><sub><b>Real-time sync tasks</b> · status, last sync and details</sub></td>
    <td align="center" width="33%"><a href="docs/images/jync_db.png" target="_blank" rel="noopener"><img src="docs/images/jync_db.png" alt="Connections" width="100%"></a><br><sub><b>Connections</b> · source and target side by side</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_xiangmu_etl.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_etl.png" alt="ETL tasks" width="100%"></a><br><sub><b>ETL tasks</b> · batches and standalone tasks</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_etl_add.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_etl_add.png" alt="New ETL task" width="100%"></a><br><sub><b>New ETL task</b> · extraction SQL, target table, write mode</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_etl_batch.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_etl_batch.png" alt="ETL batch" width="100%"></a><br><sub><b>ETL batch</b> · one schedule and one connection pair</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_db_add.png" target="_blank" rel="noopener"><img src="docs/images/jync_db_add.png" alt="New connection" width="100%"></a><br><sub><b>New connection</b> · 34 presets, test before saving</sub></td>
    <td align="center"><a href="docs/images/jync_celue.png" target="_blank" rel="noopener"><img src="docs/images/jync_celue.png" alt="Capture strategy" width="100%"></a><br><sub><b>Capture strategy</b> · polling, full compare or log CDC</sub></td>
    <td align="center"><a href="docs/images/jync_lx.png" target="_blank" rel="noopener"><img src="docs/images/jync_lx.png" alt="Task detail" width="100%"></a><br><sub><b>Task detail</b> · objects, schedule, performance, cleansing</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_cdc.jpg" target="_blank" rel="noopener"><img src="docs/images/jync_cdc.jpg" alt="Log CDC" width="100%"></a><br><sub><b>Log CDC</b> · position, last event, throughput, skip counters</sub></td>
    <td align="center"><a href="docs/images/jync_diaodu.png" target="_blank" rel="noopener"><img src="docs/images/jync_diaodu.png" alt="Scheduling" width="100%"></a><br><sub><b>Scheduling</b> · polling interval or Cron</sub></td>
    <td align="center"><a href="docs/images/jync_xn.png" target="_blank" rel="noopener"><img src="docs/images/jync_xn.png" alt="Full-load performance" width="100%"></a><br><sub><b>Full-load performance</b> · batch size and parallelism</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_db_xn.png" target="_blank" rel="noopener"><img src="docs/images/jync_db_xn.png" alt="Database performance" width="100%"></a><br><sub><b>Database performance</b> · latency, availability, in-database figures and capacity curves</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_xn.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_xn.png" alt="Sync-task performance" width="100%"></a><br><sub><b>Sync-task performance</b> · rows, lag, failures and change mix</sub></td>
    <td align="center"><a href="docs/images/jync_check.png" target="_blank" rel="noopener"><img src="docs/images/jync_check.png" alt="Row-count audit" width="100%"></a><br><sub><b>Row-count audit</b> · source/target COUNT comparison</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_lqx.png" target="_blank" rel="noopener"><img src="docs/images/jync_lqx.png" alt="Column cleansing" width="100%"></a><br><sub><b>Column cleansing</b> · per table and column, before writes</sub></td>
    <td align="center"><a href="docs/images/jync_data_yulan.png" target="_blank" rel="noopener"><img src="docs/images/jync_data_yulan.png" alt="Data preview" width="100%"></a><br><sub><b>Data preview</b> · first 100 rows on each side</sub></td>
    <td align="center"><a href="docs/images/jync_log.png" target="_blank" rel="noopener"><img src="docs/images/jync_log.png" alt="Change log" width="100%"></a><br><sub><b>Change log</b> · object, type, rows, elapsed</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_warn.png" target="_blank" rel="noopener"><img src="docs/images/jync_warn.png" alt="Alert notifications" width="100%"></a><br><sub><b>Alert notifications</b> · Webhook, format, categories, cooldown</sub></td>
    <td align="center"><a href="docs/images/jync_shenji.png" target="_blank" rel="noopener"><img src="docs/images/jync_shenji.png" alt="Audit log" width="100%"></a><br><sub><b>Audit log</b> · sign-ins and operations</sub></td>
    <td align="center"><a href="docs/images/jync_sys_log.png" target="_blank" rel="noopener"><img src="docs/images/jync_sys_log.png" alt="System log" width="100%"></a><br><sub><b>System log</b> · file, level and keyword search</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_renzheng.png" target="_blank" rel="noopener"><img src="docs/images/jync_renzheng.png" alt="External authentication" width="100%"></a><br><sub><b>External authentication</b> · credentials, gateway header, ticket</sub></td>
    <td align="center"><a href="docs/images/jync_ai.png" target="_blank" rel="noopener"><img src="docs/images/jync_ai.png" alt="AI settings" width="100%"></a><br><sub><b>AI settings</b> · several providers, one enabled</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_suoyin_ai.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_suoyin_ai.png" alt="AI draft" width="100%"></a><br><sub><b>AI draft</b> · candidate streams into the editor</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_system.png" target="_blank" rel="noopener"><img src="docs/images/jync_system.png" alt="System info" width="100%"></a><br><sub><b>System info</b> · runtime parameters and version</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_batch.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_batch.png" alt="Bulk creation" width="100%"></a><br><sub><b>Bulk creation</b> · bins by table size, creates many tasks</sub></td>
    <td align="center"><a href="docs/images/jync_kanban_anye.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban_anye.png" alt="Dark theme" width="100%"></a><br><sub><b>Dark theme</b> · the whole palette switches together</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_kanban_en.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban_en.png" alt="English UI" width="100%"></a><br><sub><b>English UI</b> · one-click language switch</sub></td>
    <td align="center"><a href="docs/images/jync_zishiying.png" target="_blank" rel="noopener"><img src="docs/images/jync_zishiying.png" alt="Narrow layout" width="100%"></a><br><sub><b>Narrow layout</b> · nav collapses, wide tables scroll</sub></td>
  </tr>
</table>

> Screenshots open at full resolution in a new tab; Gitee adds its built-in image zoom.
>
> The row-count audit runs only after you confirm it: tables are checked one at a time with `COUNT(*)` on each side, 15-second timeout per side; missing tables, timeouts and other errors are marked separately and one side's failure does not hide the other's result.

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
## Full-load performance and tuning

A full load happens once but often decides the length of the migration window. Jync prints one throughput line per table and per parallel worker, splitting "slow" into three actionable shares.

### Reading the throughput line

```text
table=orders thread=full-load-orders rows=500000 changed=500000 elapsed=2.3s rows/s=216000.0 read=38% write=52% commit=10% batches=250 rowsPerBatch=2000
```

| Field | Meaning | How to read it |
|---|---|---|
| `table` / `thread` | Table and worker thread | One line per worker when parallel; the thread is named `full-load-orders` |
| `rows` / `changed` | Rows read / rows actually written | A gap means a re-read comparison; rounds with `changed=0` stay at DEBUG |
| `elapsed` / `rows/s` | Elapsed time and throughput | The main metric for comparing rounds and settings |
| `read%` | Source fetch + row mapping share | High means the source cursor, network or mapping is the bottleneck |
| `write%` | Cleansing + binding + `addBatch` + `executeBatch` share (excluding commit) | High means target writes, constraints or LOB binding is the bottleneck |
| `commit%` | Target `COMMIT` wait share | High means redo, standby sync or disk latency is the bottleneck |
| `batches` / `rowsPerBatch` | Batch count / rows per batch | `rowsPerBatch` near the configured size means the driver really batches |

`read% + write% + commit%` add up to 100%. Only rounds with `rows >= 1000` and `changed > 0` log at INFO; small and unchanged rounds drop to DEBUG.

### Find the bottleneck before changing settings

| Symptom | Usual cause | First move |
|---|---|---|
| `commit%` is the largest | Slow target redo, slow disk, Data Guard sync transport, oversized transaction | Raise batch 2000 → 5000 to commit less often; check storage and standby; keep indexes deferred |
| `write%` is the largest | Target binding / network round trips / FK and index maintenance / LOBs | Batch 2000 → 5000; suspend target FKs during the load; inspect LOB binding and indexes |
| `read%` is the largest | Source full or wide scans, unindexed cursor, busy source | Index the cursor column; raise fetch to 2000–5000 (no effect on MySQL sources); parallelism 1 → 2 → 4; schedule off-peak |
| `rowsPerBatch` is persistently below half the batch size | The driver does not batch, or LOBs force single-row commits | Check driver version and LOB configuration; confirm the byte budget is not filled by one value |
| None is high but `rows/s` is low | High network RTT, small batches, single thread | Keep the batch at 1000 or more; parallelism 1 → 2 (~+57%); check link latency |

### How to set the parameters

| Setting | Default | Allowed range (hard limit) | Advice | Effect and trade-off |
|---|---|---|---|---|
| `sync.full-load-batch-size` | `2000` | **1–20000**, clamped to 20000; task-level ≤ 0 means unset | **1000–5000**; 500–2000 for wide tables or LOBs | Rows per batch. Larger batches commit less and run faster; past 5000 gains flatten while rollback and memory costs grow |
| `sync.full-load-fetch-size` | `2000` | **1–20000**, clamped to 20000 | **1000–5000**; 100–1000 for LOB tables; 1–10 when a single value exceeds 1MB | Source result-set batch, read side only. Lower it under memory pressure; MySQL sources always stream and ignore it |
| `sync.full-load-parallelism` | `2` | **1–8**, capped by the pool (below); task-level ≤ 0 means unset | **1–4**; 1 on shared production, 2–4 in a dedicated migration window | Workers per table. 1→2 helps most; 2→4 adds ~15–20%; beyond that mostly loads both databases |
| `sync.chunk-size` | `100000` | **≥ 1**; `0` or negative disables chunking; no upper limit | **50000–200000**; avoid below 10000 or above 500000 | Rows per chunk, and the most a crash re-reads. Too small adds boundary queries and checkpoint writes; too large lengthens re-reads and source transactions |
| `sync.max-pool-size` | `5` | No upper limit, treated as at least 2 | **5–10**; derive from parallelism, below | Connection-pool cap per database. Each worker holds one source and one target connection; a small pool only queues |
| `sync.initial-load-insert-first` | `true` | on / off | on | Empty targets take INSERT instead of upsert; tables with rows still use the idempotent upsert |
| `sync.defer-secondary-indexes` | `true` | on / off | on | Non-unique secondary indexes are created after the load; primary and unique indexes are always immediate |
| Suspend target FKs during load | on | on / off | on | A task-level option that avoids constraint failures from write order and row-by-row checks |

**Parallelism and the pool**: when source and target are distinct connection configurations, `max-pool-size` must be **≥ parallelism + 1** (the main path holds one source and one target connection); when both sides share one configuration, it must be **≥ 2 × parallelism + 2**. With the default pool of 5, that allows up to 4 workers in the first case and 1 in the second. The effective parallelism is `min(configured, 8, what the pool admits)`, and the log says so when it is lowered. When several tasks share one configuration, the remaining slots are divided among the active tasks.

**Out-of-range behavior**: batch or fetch above 20000 and parallelism above 8 are clamped, not rejected, and the log notes it; `chunk-size ≤ 0` disables chunking and the table falls back to one streamed read (parallelism then has no effect).

**Scope**: these settings affect first full loads and baseline rebuilds only, never incremental polling or CDC replay. Global values live in `application.yml`; batch size and parallelism can be overridden per task on the "Full-load performance" card (blank inherits the global value), and the rest are read-only globals.

### Measured baseline

Single-host container, 500k-row Oracle table, batch 2000:

| Scenario | Configuration | Result |
|---|---|---|
| Empty-target write mode | `MERGE` vs `INSERT` | ~84k rows/s vs **211k rows/s** |
| Single-table parallelism | 1 / 2 / 4 workers | 117k / 184k / **216k rows/s** |
| Secondary indexes | Maintained while writing vs built after | 11.1s vs **5.9s + 0.5s** (~47% faster) |

> The baseline shows the direction of each setting, not a promise. Actual throughput depends on disks, network, driver versions, table width and concurrent load on both sides.

### Tuning procedure

1. **Take a single-table baseline**: parallelism 1, batch 2000; note `rows/s` and the `read/write/commit` shares.
2. **Look at the largest share**: `commit%` → raise batch from 2000 to 5000 first; `write%` → inspect target constraints, indexes and LOBs; `read%` → inspect the source cursor and scan range, then raise fetch to 5000.
3. **Then add parallelism**: confirm the pool is large enough (distinct configurations `max-pool-size ≥ parallelism + 1`, shared `≥ 2 × parallelism + 2`), go from 2 to 4, change one variable at a time and re-read the log; past 4 returns fall off sharply.
4. **Confirm batching works**: `rowsPerBatch` should approach the batch size; persistently below half means the driver is not batching or LOBs are splitting batches.
5. **Off-peak on production**: a shared database runs parallelism 1 with batches of 1000–2000; a dedicated migration window can go to 2–4 with chunks of 50k–200k.

### Performance curves and sampling

The full-load log answers "why was this round slow"; the performance pages answer "what happened in the last hour, day or week". Both pages read samples written to the metadata store by background samplers; opening a page does not query the monitored servers.

| Setting | Default | Meaning |
|---|---|---|
| `metrics.enabled` | `true` | Master sampling switch; off produces no new samples, and stored samples remain until pruned |
| `metrics.retention-days` | `7` | Days of history to keep; `≤ 0` disables pruning |
| `metrics.app-interval-ms` | `60000` | Process CPU / heap / GC and host-resource sampling interval |
| `metrics.task-interval-ms` | `60000` | Per-task rows, lag and failure sampling interval |
| `metrics.db-interval-ms` | `300000` | Connection health and in-database figures (sessions, QPS, cache hit, lock waits); opens a session on the monitored server |
| `metrics.db-initial-delay-ms` | `30000` | First connection sample delay; data appears about 30 s after a restart instead of a full interval |
| `metrics.capacity-interval-ms` | `3600000` | Table / row / space sweep interval; catalog statistics exist for MySQL, Oracle, SQL Server, PostgreSQL and DB2 |
| `metrics.prometheus-token` | *(empty)* | Prometheus scrape token; blank keeps `/api/metrics/prometheus` at 404 |

- **Database performance** (admin): connection latency and availability, in-database sessions / QPS / cache hit / lock waits, plus table, row and space curves; switch between 1 h / 24 h / 7 days.
- **Sync-task performance**: synced rows, lag, consecutive failures, and the "rows synced per interval", "change-type mix" and "busiest tables" panels.
- **Semantics**: the rows series sums newly synced rows per bucket; polling tasks measure lag from the last successful cycle, CDC from the source commit time; buckets without a sample stay gaps rather than zeros.
- **Cost and permissions**: process and task samples are local reads; a connection sample opens a session, and in-database figures read the server's own statistics views — a missing privilege only hides that figure. Set `metrics.enabled: false` to stop sampling without touching the sync path.

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

**34 built-in types**: MySQL, MariaDB, Oracle, SQL Server, DB2, PostgreSQL, openGauss, **Huawei GaussDB**, **DM (达梦)**, **Kingbase**, **OceanBase**, **TiDB**, **ZTE GoldenDB**, **Tencent TDSQL (MySQL edition)**, **Alibaba PolarDB (MySQL edition)**, **GreatDB**, **UPDRDB**, **KunDB**, **RASESQL**, **SeaboxSQL**, **Highgo**, **Vastbase**, **YashanDB**, **GBase**, **Oscar**, **Apache Doris**, **SAP HANA**, **ClickHouse**, **Apache Hive**, **Apache Impala**, **Elasticsearch**, **MongoDB**, **MongoDB (native driver)**, H2.

- **Custom types**: JDBC URL, driver class and driver jar path, loaded at runtime in an isolated `URLClassLoader`; pick a dialect family (generic / Oracle / MySQL / PostgreSQL / SQL Server / DB2).
- **Bundled drivers**: everything except **GBase**, **Oscar**, **Huawei GaussDB**, **SAP HANA**, **ClickHouse**, **Hive**, **Impala**, **Elasticsearch** and **MongoDB (Atlas SQL)** ships with the jar. Those vendors publish no redistributable artifact (SAP's ngdbc is on Maven Central but its licence still forbids redistribution), so supply the jar and path; the driver class can be overridden on the same card.
- **Protocol-compatible presets**: GreatDB, UPDRDB and KunDB speak the MySQL protocol; RASESQL and SeaboxSQL speak the PostgreSQL protocol, reusing bundled drivers and dialect families.
- **SAP HANA**: its own dialect family; full structure sync and polling data sync (timestamp / primary-key increments), writing with `UPSERT … WITH PRIMARY KEY`, paging with `LIMIT / OFFSET`, replacing views, procedures and functions with `CREATE OR REPLACE`, and table comments with `COMMENT ON`. HANA does not expose its transaction log — **true CDC needs SAP SLT or SDI**, which this tool does not provide.
- **Dedicated migration is not JDBC**: none of Easysearch, MongoDB or KingbaseES appears as a `DatabaseType` in the real-time sync or ETL capability matrix for this feature. Each direction uses its own dedicated connection and task to copy whole objects once, independent of the JDBC connections, sync tasks and ETL tasks above.
  - **Elasticsearch / OpenSearch → Easysearch**: search connections plus whole-index copying (settings, mappings, aliases and documents).
  - **MongoDB → MongoDB**: native `mongodb-driver-sync` connections; collection options, indexes and documents are copied with `_id` unchanged.
  - **MongoDB → KingbaseES**: columns are inferred from a sample, then a table is created and filled; nested documents and arrays become JSON text in a TEXT column and are not expanded into columns.
  - Credentials live only in encrypted fields and are never echoed back; **MongoDB URIs and Kingbase JDBC URLs must not contain a password** (Kingbase also forbids `user` / `password` parameters), and neither connection tests nor run logs print the URI. See [Known limitations](docs/known-limitations.md#11-dedicated-migration-es--opensearch--easysearch-mongodb--mongodb-mongodb--kingbasees).
- **Analytic / search engines (sync sources + ETL)**: Apache Doris, ClickHouse, Apache Hive, Apache Impala, Elasticsearch, MongoDB. They have no log-based CDC, so they are **real-time sync sources only** — polling or full compare, **never a target** (no table creation, no upsert); their tables are always read as keyless. See [Known limitations](docs/known-limitations.md) (§3.6). In ETL they can be either source or target, except Elasticsearch and MongoDB, whose SQL interfaces are read-only (ETL sources only). MongoDB has two paths: the **MongoDB** preset uses the official Atlas SQL JDBC driver (Atlas and Enterprise Advanced with the SQL Interface; about 40 MB, supplied by you); **MongoDB (native driver)** uses the bundled Java driver and connects to any `mongod`, taking a collection name and an optional Extended JSON filter — no SQL and no external jar.
- **Huawei GaussDB** is a different product from openGauss: driver `com.huawei.gaussdb.jdbc.Driver`, URL prefix `jdbc:gaussdb://`, PostgreSQL dialect family.
- **PolarDB / TDSQL editions**: MySQL editions have their own presets; PostgreSQL editions use PostgreSQL; PolarDB Oracle-compatible uses a custom type.
- Driver versions and licenses: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

<a name="deploy"></a>
## Deployment

### Requirements

| Item | Requirement |
|---|---|
| JDK | **17+** |
| Maven | 3.6+ (source build only) |
| Port | `8080` by default |
| Memory / disk | Heap ≥ 512MB recommended, 1GB free; size the heap for the largest LOB when syncing LOBs |
| Working directory | Must be fixed: `./data` (metadata store and key) and `./logs` are created relative to the process working directory |

The first start creates `./data` and `./logs`. **Do not start from different directories**: "my settings disappeared" means it connected to another empty metadata store.

```bash
sudo mkdir -p /opt/jync && sudo chown -R jync:jync /opt/jync && cd /opt/jync
```

Suggested layout: `jync.jar`, `application.yml`, `data/` (H2 metadata store and `<db>.key`, back both up), `logs/`.

### Get the jar

- GitHub: <https://github.com/vfaner/jync/releases/latest>
- Gitee: <https://gitee.com/super_rgh/jync/releases/latest>
- AtomGit (mirror): <https://gitcode.com/vfaner/jync/releases/latest>

```bash
git clone https://github.com/vfaner/jync.git
cd jync
mvn clean package -DskipTests
cp target/jync.jar /opt/jync/
```

In mainland China use Gitee: `git clone https://gitee.com/super_rgh/jync.git`. Running the tests executes about 4,000 cases.

### Production configuration

Create `application.yml` next to the jar:

```yaml
server:
  port: 8080

sync:
  poll-interval: 2000              # global polling interval (ms)
  batch-size: 500                  # incremental / CDC replay batch (live traffic: keep small)
  fetch-size: 1000                 # incremental result-set batch
  full-load-batch-size: 2000       # first full load only: rows per batch (1-20000, 1000-5000 advised)
  full-load-fetch-size: 2000       # first full load only: source fetch size (1-20000)
  full-load-parallelism: 2         # full-load workers (1-8, 1-4 advised, capped by the pool)
  chunk-size: 100000               # full-load rows per chunk (50k-200k advised; 0 disables chunking)
  initial-load-insert-first: true  # INSERT into empty targets instead of upsert
  defer-secondary-indexes: true    # build non-unique secondary indexes after the load
  max-pool-size: 5                 # per-database pool cap (5-10 advised; must fit the parallelism)
  crypto-password: ""              # blank = generated on first boot into data/<db>.key
  crypto-salt: ""                  # blank = same; set both to manage the key yourself

  # ai:
  #   enabled: false               # air-gapped / offline: disable AI assistance

metrics:
  enabled: true                    # master switch for performance sampling
  retention-days: 7                # days of history to keep
  app-interval-ms: 60000           # process-resource sampling
  task-interval-ms: 60000          # per-task throughput sampling
  db-interval-ms: 300000           # connection health and in-database figures (opens a session)
  capacity-interval-ms: 3600000    # table / row / space sweep
  prometheus-token: ""             # blank closes the Prometheus endpoint

app:
  login:
    # Escape hatch: if external authentication becomes unreachable after local login is
    # disabled, start once with this true (or JYN_RESET_LOGIN=true) to re-enable and save
    # local login; set it back to false afterwards.
    reset-on-startup: false
  # air-gapped / offline: disable version checks
  # update-check:
  #   enabled: false
```

**External metadata store (optional)**

To drop the H2 file store, point `spring.datasource.*` at an existing database before startup; tables are created by `ddl-auto: update` on first boot:

| Metadata store | Driver class | Example URL |
|---|---|---|
| H2 (default) | `org.h2.Driver` | `jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE` |
| MySQL 8 | `com.mysql.cj.jdbc.Driver` | `jdbc:mysql://db:3306/jync?useUnicode=true&characterEncoding=utf8` |
| PostgreSQL 16 | `org.postgresql.Driver` | `jdbc:postgresql://db:5432/jync` |
| Oracle 23 | `oracle.jdbc.OracleDriver` | `jdbc:oracle:thin:@//db:1521/FREEPDB1` |

The `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` / `SPRING_DATASOURCE_DRIVER_CLASS_NAME` environment variables work too. Key points:

- **Create the database first**: MySQL needs `CREATE DATABASE jync DEFAULT CHARACTER SET utf8mb4;`; the others can be empty. JDBC drivers are bundled.
- **Set `SYNC_CRYPTO_PASSWORD` / `SYNC_CRYPTO_SALT` explicitly**: a generated key lives in the local `<db>.key` file, which an external store does not have.
- **Verified scope**: MySQL 8, PostgreSQL 16 and Oracle 23 have real-database contract tests; SQL Server and domestic databases have not been verified as metadata stores, so validate in a test environment with the same driver before production.
- **Changing the store is not a migration**: connections, tasks, cursors and checkpoints from the old H2 store do not come along.
- **Multi-replica requirement**: H2 `AUTO_SERVER=TRUE` only shares within one host; cross-host instances require an external metadata store.

Start command:

```bash
cd /opt/jync
java -Xms512m -Xmx2g -Duser.timezone=Asia/Shanghai \
  -jar jync.jar --spring.config.additional-location=file:./application.yml
```

> ⚠️ Use `--spring.config.additional-location` (layered override). The replacing `spring.config.location` drops the jar's built-in configuration: the login page shows `??login.title_zh_CN??` and a fresh deployment creates no tables.
>
> 🕐 `-Duser.timezone` also decides Cron evaluation. Pin it when the server defaults to UTC.

### First sign-in

Open `http://<server>:8080`:

| User | Role | Initial password |
|---|---|---|
| `admin` | Administrator | `123456` |
| `view` | Viewer | `123456` |

**Change the password at first sign-in**: until then only the change-password page is available. Passwords are stored with BCrypt.

### systemd service

`/etc/systemd/system/jync.service`:

```ini
[Unit]
Description=Jync heterogeneous database sync
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=jync
Group=jync
WorkingDirectory=/opt/jync
ExecStart=/usr/bin/java -Xms512m -Xmx2g -Duser.timezone=Asia/Shanghai \
  -jar /opt/jync/jync.jar --spring.config.additional-location=file:/opt/jync/application.yml
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now jync
```

### Docker and Kubernetes

```bash
docker build -t jync:1.10.0 .
docker run -d --name jync -p 8080:8080 \
  -v "$PWD/data:/app/data" -v "$PWD/logs:/app/logs" \
  -e TZ=Asia/Shanghai --restart unless-stopped jync:1.10.0
```

Or `docker compose up -d --build`. The runtime image contains only a JRE, runs as non-root and has a built-in `HEALTHCHECK`; metadata and logs land in the host `./data` and `./logs`, so rebuilding the image loses nothing.

```bash
docker build -t registry.example.com/qqmu/jync:1.10.0 .
docker push registry.example.com/qqmu/jync:1.10.0
kubectl apply -k deploy/k8s/
```

The manifests default to `jync:1.10.0`; change registry and tag with the `images` transform in `kustomization.yaml`. The Service is `ClusterIP`, so use `port-forward` or an Ingress outside the cluster. The H2 metadata store lives on a `ReadWriteOnce` PVC, so **replicas must stay at 1**; switch to an external MySQL / PostgreSQL metadata store before scaling out.

### Reverse proxy and HTTPS

```nginx
server {
    listen 443 ssl;
    server_name jync.example.com;
    ssl_certificate     /etc/nginx/certs/jync.crt;
    ssl_certificate_key /etc/nginx/certs/jync.key;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
}
```

Set `JYN_COOKIE_SECURE=true` for HTTPS. With gateway-header SSO, add the gateway to `app.security.trusted-proxies` and make sure it strips identity headers sent by clients.

### Upgrade and backup

1. Stop the service and back up `./data` (including `<db>.key`), `./logs` and `application.yml`.
2. Replace `jync.jar` and restart; the schema evolves through `ddl-auto: update`.
3. Changes made at the source during downtime are caught up by incremental sync or CDC.
4. Administrators can also use the version check and one-click upgrade on the System info page; the old jar is kept as `jync.jar.bak` and the restart script carries over the current JVM and program arguments (JDWP debug arguments excepted).

> 🔐 Back up `<db>.key` together with the metadata store. Without the key, stored connection passwords, AI keys and alert URLs cannot be decrypted and must be re-entered.

### Air-gapped and offline

- `sync.ai.enabled=false`: hides the AI menu and blocks AI drafts before any request.
- `app.update-check.enabled=false`: no GitHub / Gitee calls; the upgrade entry disappears too.
- Alerting only calls out after you configure and enable a Webhook.
- All static assets are local; no CDN or webfont.

---

<a name="config"></a>
## Configuration reference

Main settings in `application.yml` (`sync.*` prefix):

| Key | Default | Meaning |
|---|---|---|
| `sync.poll-interval` | `2000` | Global polling interval (ms) |
| `sync.batch-size` | `500` | JDBC batch for incremental polling and CDC replay |
| `sync.fetch-size` | `1000` | Incremental result-set batch |
| `sync.full-load-batch-size` | `2000` | First full load only: rows per batch, 1–20000 |
| `sync.full-load-fetch-size` | `2000` | First full load only: source fetch size, 1–20000 |
| `sync.full-load-parallelism` | `2` | Workers per table, 1–8, capped by the pool |
| `sync.chunk-size` | `100000` | Rows per chunk; `0` disables chunking |
| `sync.initial-load-insert-first` | `true` | INSERT into empty targets instead of upsert |
| `sync.defer-secondary-indexes` | `true` | Build non-unique secondary indexes after the load |
| `sync.max-pool-size` | `5` | Pool cap per database; also limits parallelism |
| `sync.pool-wait-ms` | `10000` | Wait before yielding a cycle when the pool is full |
| `sync.safety-lag-ms` | `1000` | Timestamp watermark lag |
| `sync.full-compare-max-rows` | `20000` | Automatic full-compare row ceiling |
| `sync.delete-compare-max-rows` | `0` | Chunked delete-comparison ceiling; `0` off |
| `sync.row-count-audit-interval-ms` | `60000` | Row-count audit interval; `0` off |
| `sync.row-count-repair-max-rows` | `1000` | Key-based repair ceiling before clearing the cursor and reloading; `0` off |
| `sync.max-unindexed-scan-rows` | `1000000` | Ceiling for probes on unindexed cursor columns; past it probes stop and one warning is logged; `0` off |
| `sync.stuck-threshold-ms` | `1800000` | No-progress time before a cycle counts as stuck; `0` off |
| `sync.lock-ttl-ms` | `300000` | Sync lock lease |
| `sync.reconcile-interval-ms` | `30000` | Multi-instance schedule reconciliation period |
| `sync.query-timeout-seconds` | `300` | Data-plane statement timeout; `0` off |
| `sync.copy-timeout-seconds` | `1800` | Row-copy (window query and watermark MAX) timeout; `0` off |
| `sync.metadata-query-timeout-seconds` | `60` | Metadata enumeration statement timeout |
| `sync.shutdown-wait-ms` | `20000` | Grace period for in-flight cycles at shutdown |
| `sync.change-log-retention-days` | `30` | Change-log retention |
| `sync.lob.streaming-enabled` | `true` | Master switch for streamed LOB I/O |
| `sync.lob.inline-max-bytes` | `65536` | LOB values up to this size stay in heap |
| `sync.lob.cdc-spill-threshold-bytes` | `1048576` | CDC transaction-buffer values above this spill to disk |
| `sync.lob.spill-max-bytes` / `spill-retention-hours` | `1 GiB` / `24` | Spill total cap and leftover cleanup |
| `sync.cdc.server-id-offset` | `612000` | Fake MySQL replica server-id base; id = offset + task id |
| `sync.cdc.lock-ttl-ms` | `30000` | CDC consumer lease |
| `sync.cdc.supervise-interval-ms` | `10000` | CDC lease supervision period |
| `sync.cdc.connect-timeout-ms` | `10000` | Timeout for connecting to the source |
| `sync.cdc.reconnect-backoff-ms` | `1000` | Initial reconnect backoff |
| `sync.cdc.reconnect-backoff-max-ms` | `60000` | Reconnect backoff ceiling |
| `sync.cdc.max-transaction-rows` / `max-transaction-bytes` | `1000000` / `512 MiB` | Per-source-transaction buffer cap; exceeding it parks the stream; `0` unlimited |
| `sync.cdc.logminer.batch-size` | `10000` | LogMiner rows per batch |
| `sync.cdc.logminer.idle-poll-ms` | `1000` | LogMiner idle sleep |
| `sync.cdc.logminer.missing-lob-policy` | `skip` | Out-of-line LOB that cannot be restored: `skip` counts and continues, `fail` parks |
| `sync.cdc.pg.idle-poll-ms` | `500` | PostgreSQL logical decoding idle sleep |
| `sync.cdc.pg.wal-retention-warn-bytes` | `4294967296` | Panel warning for WAL held by a slot (4 GiB) |
| `sync.cdc.mssql.idle-poll-ms` | `1000` | SQL Server CDC idle sleep |
| `sync.cdc.change-window-ms` | `10000` | CDC change-log aggregation window; `0` off |
| `sync.alert.evaluate-interval-ms` / `initial-delay-ms` | `60000` / `45000` | Alert evaluation period and first delay |
| `sync.crypto-password` / `sync.crypto-salt` | *(generated when blank)* | Encryption key for passwords and alert URLs |
| `sync.ai.enabled` | `true` | `false` removes AI menus and endpoints |
| `app.update-check.enabled` | `true` | Version check; `false` makes no outbound calls |
| `app.security.trusted-proxies` | *(empty)* | Trusted proxies; also the trust boundary of gateway-header SSO |
| `metrics.enabled` | `true` | Master switch for performance sampling; off never touches the sync path |
| `metrics.retention-days` | `7` | Days of samples to keep; `≤ 0` disables pruning |
| `metrics.app-interval-ms` | `60000` | Process-resource sampling interval |
| `metrics.task-interval-ms` | `60000` | Task throughput and lag sampling interval |
| `metrics.db-interval-ms` | `300000` | Connection health and in-database figure interval |
| `metrics.db-initial-delay-ms` | `30000` | Delay before the first connection sample |
| `metrics.capacity-interval-ms` | `3600000` | Table / row / space sweep interval |
| `metrics.prometheus-token` | *(empty)* | Prometheus scrape token; blank keeps the endpoint at 404 |

> Connection passwords, AI keys and alert Webhook URLs are stored with AES-256; the `enc:` prefix prevents double encryption. A blank key is generated on first boot.

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

**4,415 tests: 4,414 pass, and one Linux-only key-file permission case is skipped on macOS by environment.**

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
