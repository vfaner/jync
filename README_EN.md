# Jync · Heterogeneous Database Real-Time Sync

> **Jync**, pronounced "sync" — **J** for Java; the Chinese name 捷同 means "swift and identical".
> A single-JAR heterogeneous database synchronization engine: **chunked parallel full load, read-only incremental capture, second-level log CDC**.

[简体中文](README.md) | **English**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot 2.7](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen.svg)](https://spring.io/projects/spring-boot)

![Jync · Heterogeneous Database Real-Time Sync](docs/images/jync.png)

An out-of-the-box web application that keeps **structure and data** in sync between **heterogeneous databases**: start one jar, pick any two of the **26 built-in database types (plus custom types)** in a browser, and a sync pipeline is running. No Kafka, no Flink, no ZooKeeper, and not a line of job code.

- **Zero external dependencies**: a Spring Boot monolith with Thymeleaf server-side rendering, Quartz and an embedded H2 metadata store. Runs air-gapped.
- **Full load is not a bulk query import**: keyset chunking, in-table parallelism, streamed result sets, per-chunk commits and checkpoints — single-table throughput reaches the **hundreds of thousands of rows per second** range.
- **Incremental is one indexed query**: the default polling mode reads only rows past the cursor, so one read-only account is enough. Four source families can switch to log CDC for second-level latency.
- **Structure travels with data**: missing tables, indexes, views and procedures are created automatically, with built-in dialect type mapping and function conversion.
- **Resumable by design**: crashes, network drops and killed processes resume from the last checkpoint; every write is idempotent, and changes made while Jync was down are caught up on restart.

| Resource | Link |
|---|---|
| **Download (GitHub)** | [github.com/vfaner/jync/releases/latest](https://github.com/vfaner/jync/releases/latest) |
| **Download (Gitee)** | [gitee.com/super_rgh/jync/releases/latest](https://gitee.com/super_rgh/jync/releases/latest) |
| **Mirror (AtomGit)** | [gitcode.com/vfaner/jync](https://gitcode.com/vfaner/jync) |
| **Website** | [jync.qqmu.com](https://jync.qqmu.com) |
| **Video walkthrough** | [Bilibili · 26 minutes, from connection to real-time sync](https://www.bilibili.com/video/BV1iHYJ6vEEd) |

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

**Born on real migration projects.** When a public-sector or enterprise system moves from MySQL, Oracle or SQL Server to a domestic database such as DM (达梦), Kingbase or OceanBase, the migration rarely ends when the data is copied. Old and new systems run side by side, cutover happens in batches, and read-only archives coexist with live services — all of which require the two databases to **stay consistent for weeks or months**.

**Existing tools always leave one gap.** Commercial sync products are licensed per node or per database, with long procurement cycles and high cost. Log-based tools such as Debezium and Flink CDC are capable, but require Kafka, Flink and job orchestration before the first row moves. DataX and Kettle excel at one-off transfers and ETL, but do not own long-running incremental sync, structure synchronization or resume-from-checkpoint.

**So the sync logic became a product.** Jync distills what migration teams kept rebuilding into a single web application: one jar, a browser for connections, tables, scheduling and start/stop, and no scripts or message queues to operate. It is open source under MIT, auditable, and fully self-hostable.

**Design goals**

- **One jar**: metadata store, scheduler and web UI included — run it on an isolated network.
- **Minutes to first sync**: connections can be tested before saving, objects are checkboxes, and every setting has a default.
- **Structure is a first-class citizen**: DDL, indexes, views, procedures and type mapping are the tool's job, not yours.
- **Domestic databases first**: DM, Kingbase, OceanBase, TiDB and GaussDB have dedicated dialects rather than "generic JDBC might connect".
- **Observable**: rows, elapsed time, throughput and bottleneck shares are written to the log and shown in the UI for every cycle.

---

<a name="features"></a>
## Capabilities

| Area | What Jync does |
|---|---|
| Full load | Keyset chunking + in-table parallelism + streamed reads + per-chunk checkpoints; INSERT-first into empty targets, deferred secondary indexes; hundreds of thousands of rows/s |
| Incremental sync | Timestamp, row SCN, identity column or full comparison, selected automatically; one cursor-range query; safety-lagged watermark; cursor advances only after commit |
| Log CDC | MySQL binlog, Oracle LogMiner, PostgreSQL logical decoding, SQL Server CDC; long-lived streaming replay with second-level latency |
| Structure sync | Tables, indexes, views, procedures and functions; type mapping, function conversion, identifier quoting, pagination and DDL overrides |
| Data shaping | Column cleansing (7 rule types), table-name mapping, read-only data preview, on-demand row-count audit, task cloning, bulk task creation (binned by table size) |
| Reliability | Idempotent upsert, checkpoint resume, database leases, at-least-once delivery, automatic catch-up after downtime |
| Observability | Dashboard, change log, audit log, system log, per-table throughput log and CDC status panel, on-demand row-count audit |
| Delivery | jar / Docker / Kubernetes; Chinese and English UI, light and dark themes; responsive layout usable on phones; local accounts plus three SSO modes |

---

<a name="screenshots"></a>
## Screens

<table>
  <tr>
    <td align="center" width="33%"><a href="docs/images/jync_kanban.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban.png" alt="Dashboard" width="100%"></a><br><sub><b>Dashboard</b> · connections, tasks, tables and 24h changes</sub></td>
    <td align="center" width="33%"><a href="docs/images/jync_xiangmu.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu.png" alt="Sync tasks" width="100%"></a><br><sub><b>Sync tasks</b> · start and stop several tasks</sub></td>
    <td align="center" width="33%"><a href="docs/images/jync_db.png" target="_blank" rel="noopener"><img src="docs/images/jync_db.png" alt="Connections" width="100%"></a><br><sub><b>Connections</b> · source and target side by side</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_db_add.png" target="_blank" rel="noopener"><img src="docs/images/jync_db_add.png" alt="New connection" width="100%"></a><br><sub><b>New connection</b> · 26 presets, test before saving</sub></td>
    <td align="center"><a href="docs/images/jync_celue.png" target="_blank" rel="noopener"><img src="docs/images/jync_celue.png" alt="Capture strategy" width="100%"></a><br><sub><b>Capture strategy</b> · polling, full compare or log CDC</sub></td>
    <td align="center"><a href="docs/images/jync_lx.png" target="_blank" rel="noopener"><img src="docs/images/jync_lx.png" alt="Task detail" width="100%"></a><br><sub><b>Task detail</b> · objects, schedule, performance, cleansing</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_cdc.png" target="_blank" rel="noopener"><img src="docs/images/jync_cdc.png" alt="Log CDC" width="100%"></a><br><sub><b>Log CDC</b> · position, lag, throughput, skip counters</sub></td>
    <td align="center"><a href="docs/images/jync_diaodu.png" target="_blank" rel="noopener"><img src="docs/images/jync_diaodu.png" alt="Scheduling" width="100%"></a><br><sub><b>Scheduling</b> · polling interval or Cron</sub></td>
    <td align="center"><a href="docs/images/jync_xn.png" target="_blank" rel="noopener"><img src="docs/images/jync_xn.png" alt="Full-load performance" width="100%"></a><br><sub><b>Full-load performance</b> · batch size and parallelism</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_lqx.png" target="_blank" rel="noopener"><img src="docs/images/jync_lqx.png" alt="Column cleansing" width="100%"></a><br><sub><b>Column cleansing</b> · per table and column, applied before writes</sub></td>
    <td align="center"><a href="docs/images/jync_data_yulan.png" target="_blank" rel="noopener"><img src="docs/images/jync_data_yulan.png" alt="Data preview" width="100%"></a><br><sub><b>Data preview</b> · first 100 rows on each side</sub></td>
    <td align="center"><a href="docs/images/jync_log.png" target="_blank" rel="noopener"><img src="docs/images/jync_log.png" alt="Change log" width="100%"></a><br><sub><b>Change log</b> · object, type, rows, elapsed</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_shenji.png" target="_blank" rel="noopener"><img src="docs/images/jync_shenji.png" alt="Audit log" width="100%"></a><br><sub><b>Audit log</b> · sign-ins and operations</sub></td>
    <td align="center"><a href="docs/images/jync_sys_log.png" target="_blank" rel="noopener"><img src="docs/images/jync_sys_log.png" alt="System log" width="100%"></a><br><sub><b>System log</b> · file, level and keyword search</sub></td>
    <td align="center"><a href="docs/images/jync_renzheng.png" target="_blank" rel="noopener"><img src="docs/images/jync_renzheng.png" alt="External authentication" width="100%"></a><br><sub><b>External authentication</b> · credentials, gateway header or ticket</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_ai.png" target="_blank" rel="noopener"><img src="docs/images/jync_ai.png" alt="AI settings" width="100%"></a><br><sub><b>AI settings</b> · several providers, one enabled</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_suoyin_ai.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_suoyin_ai.png" alt="AI drafting" width="100%"></a><br><sub><b>AI drafting</b> · candidate SQL streams into the editor</sub></td>
    <td align="center"><a href="docs/images/jync_system.png" target="_blank" rel="noopener"><img src="docs/images/jync_system.png" alt="System info" width="100%"></a><br><sub><b>System info</b> · runtime settings and version</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_kanban_anye.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban_anye.png" alt="Dark theme" width="100%"></a><br><sub><b>Dark theme</b> · the whole palette switches</sub></td>
    <td align="center"><a href="docs/images/jync_kanban_en.png" target="_blank" rel="noopener"><img src="docs/images/jync_kanban_en.png" alt="English UI" width="100%"></a><br><sub><b>English UI</b> · one toggle, ZH / EN</sub></td>
    <td align="center"><a href="docs/images/jync_xiangmu_add.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_add.png" alt="New task" width="100%"></a><br><sub><b>New task</b> · pick a tested connection on each side</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/images/jync_xiangmu_batch.png" target="_blank" rel="noopener"><img src="docs/images/jync_xiangmu_batch.png" alt="Bulk create" width="100%"></a><br><sub><b>Bulk create</b> · split tables by estimated size into several tasks</sub></td>
    <td align="center"><a href="docs/images/jync_check.png" target="_blank" rel="noopener"><img src="docs/images/jync_check.png" alt="Row-count audit" width="100%"></a><br><sub><b>Row-count audit</b> · source vs target COUNT with differences flagged</sub></td>
    <td></td>
  </tr>
</table>

> The screenshots are high-resolution originals; clicking one opens the original in a new tab, and Gitee adds its built-in zoom preview on top.
>
> **Row-count audit runs on demand.** After you confirm, tables are counted serially with `COUNT(*)`; each side has a 15-second query timeout, and a missing table, timeout or other error is flagged on that side without hiding the other side's count.

---

<a name="engine"></a>
## Sync engine: full load, incremental and log CDC

Jync has three data paths, one for "how to move it the first time", one for "how to keep up", one for "how to get to second-level latency".

```
Initial full load:  keyset chunks → N workers in parallel → streamed reads → per-batch commit → checkpoint
Incremental poll:   cursor > last AND cursor <= watermark → batched upsert → advance cursor
Log CDC:            record position → chunked baseline → long-lived replay → advance position after commit
```

### Initial full load: chunked, parallel, streamed

A full load is not `SELECT *` followed by bulk `INSERT`. It is a bounded, interruptible, resumable pipeline:

```
Source cursor (fetch-size streaming)
  → row mapping / column cleansing (in memory, per row)
  → JDBC batch (bounded by rows and bytes)
  → target commit (per batch)
  → checkpoint (per chunk)
```

- **Streamed reads**: the result set is fetched in `full-load-fetch-size` increments; rows are mapped, cleansed and bound one at a time. Heap usage tracks the current batch and the current large value, not the table size.
- **Keyset chunking**: tables with a primary key are paged by key cursor, `chunk-size` rows per chunk (100,000 by default). Each chunk commits and checkpoints independently, so a crash re-reads one chunk, not the whole table.
- **In-table parallelism**: a single-column integer key is split arithmetically into disjoint `[MIN, MAX]` ranges; text, UUID and composite keys use boundaries sampled from the source key order. Each worker opens its own connections, up to 8. Ranges never overlap, writes are idempotent, and completion order cannot affect correctness.
- **Keyless tables**: when the target is empty and a sortable column exists, OFFSET paging resumes from the target's row count; otherwise a single streamed read is used.
- **Write-side optimizations**: plain `INSERT` instead of upsert into an empty target; non-unique secondary indexes built after the load; target foreign keys can be suspended during the write.
- **When it runs**: only on the first start, a manual progress reset, or a CDC baseline rebuild. Routine polling moves increments only.

### Incremental polling: read only what changed

Every table costs exactly one query per cycle:

```sql
SELECT ... FROM t WHERE cursor > :last AND cursor <= :watermark
```

- **Cursor selection is automatic**: `TIMESTAMP` catches inserts and updates, `ROW_SCN` catches row-level Oracle changes, and `IDENTITY` catches inserts only. Small keyed tables can use `FULL_COMPARE` to re-read the whole table on each interval: automatic selection only covers tables at or below `sync.full-compare-max-rows` (default 20000), while choosing it explicitly at task level bypasses that cap and re-reads the source table every poll interval. Deletes are handled only when row deletion is enabled and the table is within the cap. The task page and the start-up log list tables that are over the cap or too heavy for the current poll interval, with a suggested interval.
- **Watermark lag**: the high-water mark is pulled back by `safety-lag-ms`, so transactions that commit slightly out of clock order are re-read on the next cycle and absorbed by the idempotent upsert.
- **The cursor advances only after the target commit**, so the failure semantics are at-least-once: no row is lost.
- The default polling mode never reads the source's binlog and installs no triggers; one read-only account is enough.

### Log CDC: second-level streaming replay

Five source families can run log CDC per task, reading the source's log and seeing `INSERT / UPDATE / DELETE` directly:

| Engine | Source prerequisites (probed item by item) | Position |
|---|---|---|
| MySQL binlog | 5.7+; `log_bin=ON`, `binlog_format=ROW`, `binlog_row_image=FULL`; grants `REPLICATION SLAVE` and `REPLICATION CLIENT`; binlog retained ≥ 24h | binlog file + offset (GTID included) |
| Oracle LogMiner | 11g+; `ARCHIVELOG` mode; database-level `SUPPLEMENTAL LOG DATA`; table-level `SUPPLEMENTAL LOG DATA (ALL) COLUMNS`; mining privileges; archive retention ≥ 24h | SCN |
| DM LogMiner | DM8; `ARCH_MODE=Y`; `RLOG_APPEND_LOGIC=2` (full old image in redo); `V$ARCHIVED_LOG` readable and `DBMS_LOGMNR` executable; only archived files can be mined, so the archive-switch interval is the CDC latency floor (schedule `ALTER SYSTEM SWITCH LOGFILE` about every minute) | SCN (`MAX(NEXT_CHANGE#)`) |
| PostgreSQL logical decoding | PostgreSQL 11+ (position advance uses `pg_replication_slot_advance`), plus PG-family domestic kernels (openGauss, GaussDB, Kingbase, Highgo, Vastbase, Oscar, RaseSQL, SeaBoxSQL); `wal_level=logical`; `REPLICATION` grant (not required by openGauss); table `REPLICA IDENTITY` not `NOTHING`; on openGauss prefer `enable_slot_log=on` or the slot does not survive a restart | LSN |
| SQL Server CDC | 2012+; database-level `sp_cdc_enable_db`; SQL Server Agent running; `cdc_Admin` / `cdc_reader` membership | LSN |

- **One flow for all five**: record the position → chunked full-load baseline → long-lived replay. DM shares the Oracle LogMiner pipeline; only the position source, mineable files and package calls go through a per-vendor dialect. The position is written before the baseline, so changes during the baseline cannot be missed.
- **Low idle latency**: while data exists, the next round starts immediately with no extra wait; only a genuinely empty stream sleeps (1s for LogMiner and SQL Server, 500ms for PostgreSQL, 50ms minimum). MySQL binlog is server-pushed and not polled.
- **Multi-instance takeover**: the CDC stream for one task is guarded by a database lease; if the owning node dies, another node takes over within 60s.
- **Consistency contract**: commit to the target, re-check the lease, then advance the position. After a crash the stream replays from the last position, and the overlapping window is absorbed by idempotent upserts.

### Speed and scale

On a single-container benchmark (a 500,000-row Oracle table), the full load measured **216,000 rows/s**. With INSERT-first, an empty-table load rose from 84,000 rows/s with MERGE to 211,000 rows/s. Log CDC delivers second-level end-to-end latency and replays continuously while data exists. For the common migration shape — dozens of tables, millions to tens of millions of rows — Jync's latency and throughput are in the same class as Flink CDC; the difference is that the deployment surface is one jar, without a Kafka or Flink cluster to operate.

---

<a name="performance"></a>
## Full-load performance and tuning

A full load happens once, but it often decides how long the whole migration window is. Jync prints one throughput line per table and per worker, turning "slow" into three actionable shares.

### Reading the throughput log

```text
table=orders thread=full-load-orders rows=500000 changed=500000 elapsed=2.3s rows/s=216000.0 read=38% write=52% commit=10% batches=250 rowsPerBatch=2000
```

| Field | Meaning | How to read it |
|---|---|---|
| `table` / `thread` | Table and executing thread | One line per worker; thread names look like `full-load-orders` |
| `rows` / `changed` | Rows read / rows actually written | A difference means this cycle re-read and compared; cycles with `changed=0` log at DEBUG |
| `elapsed` / `rows/s` | Elapsed and throughput | The primary metric for comparing cycles and settings |
| `read%` | Source fetch + row mapping share | High: the source cursor, network or mapping is the bottleneck |
| `write%` | Cleansing + binding + `addBatch` + `executeBatch` share (excluding commit) | High: target writes, constraints or large-value binding is the bottleneck |
| `commit%` | Target `COMMIT` wait share | High: redo, standby synchronization or disk latency is the bottleneck |
| `batches` / `rowsPerBatch` | Batch count / actual rows per batch | `rowsPerBatch` near the configured size means batching really happens |

`read% + write% + commit%` always sums to 100%. INFO is emitted only when `rows >= 1000` and `changed > 0`; small windows and no-change cycles drop to DEBUG to keep the log quiet.

### Diagnose first, then tune

| Symptom | Usual cause | What to do first |
|---|---|---|
| `commit%` is the largest share | Slow target redo, slow disk, synchronous standby, large transactions | Raise the batch from 2000 to 5000 to commit less often; check storage and standbys; keep deferred indexes enabled |
| `write%` is largest | Target binding, network round trips, foreign keys and index maintenance, large values | Raise the batch from 2000 to 5000; suspend target foreign keys; inspect large-value binding and indexes |
| `read%` is largest | Full or wide scans on the source, an unindexed cursor, a busy source | Confirm the cursor column is indexed; raise fetch to 2000–5000 (no effect on a MySQL source); step parallelism 1 → 2 → 4; run off-peak |
| `rowsPerBatch` stays below half the batch size | The driver is not batching, or a large value is forcing single-row commits | Check driver version and LOB settings; confirm the batch byte budget is not filled by one value |
| No share dominates but `rows/s` is low | High network RTT, tiny batches, a single thread | Keep batch at 1000 or more; step parallelism 1 → 2 (about +57% in the baseline); check link latency |

### Tuning the knobs

| Setting | Default | Accepted range (hard limit) | Recommended | Effect and trade-off |
|---|---|---|---|---|
| `sync.full-load-batch-size` | `2000` | **1–20000**; anything above is clamped to 20000; a task-level value ≤ 0 means unset | **1000–5000**; 500–2000 for wide or LOB-heavy tables | Rows per batch. Larger batches commit less often and run faster; past 5000 the gain flattens while rollback and memory costs keep rising |
| `sync.full-load-fetch-size` | `2000` | **1–20000**; anything above is clamped to 20000 | **1000–5000**; 100–1000 for LOB-heavy tables; 1–10 when a single value exceeds 1 MB | Source result-set batch, affecting reads only. Lower it when memory is tight; a MySQL source always streams row by row, so the value has no effect there |
| `sync.full-load-parallelism` | `2` | **1–8**, further capped by the pool (below); a task-level value ≤ 0 means unset | **1–4**; 1 on shared production databases, 2–4 in a dedicated migration window | Workers per table. 1→2 is the biggest jump; 2→4 adds roughly 15–20%; beyond that it is mostly extra load on both databases |
| `sync.chunk-size` | `100000` | **≥ 1**; `0` or a negative value disables chunking; no upper limit | **50000–200000**; avoid below 10000 or above 500000 | Rows per chunk, and the most a crash can re-read. Too small makes the per-chunk boundary query and checkpoint write more frequent; too large lengthens re-reads and source transactions |
| `sync.max-pool-size` | `5` | No upper limit; treated as at least 2 internally | **5–10**; derive it from parallelism, below | Connection pool per database. Each worker holds one source and one target connection; a pool that is too small simply queues workers |
| `sync.initial-load-insert-first` | `true` | on / off | on | Use INSERT instead of upsert into an empty table; a table with rows still uses idempotent upsert |
| `sync.defer-secondary-indexes` | `true` | on / off | on | Build non-unique secondary indexes after the load; primary keys and unique indexes are always created up front |
| Suspend target foreign keys | on | on / off | on | A task-level option that avoids ordering failures and per-row constraint checks during the write |

**Parallelism versus pool, exactly**: with distinct source and target connection configs, `max-pool-size` must be **≥ parallelism + 1** (the main cycle holds one connection per side). When both sides share one connection config, it must be **≥ 2 × parallelism + 2**. With the default pool of 5 that allows at most 4 workers in the first case and 1 in the second. The effective value is `min(configured, 8, workers the pool can seat)`, and the log says so when it is lowered.

**Out-of-range behavior**: a batch or fetch size above 20000, or parallelism above 8, is not an error — the value is clamped and the log notes it. `chunk-size ≤ 0` disables chunking, so the table falls back to one streamed read (parallelism then does not apply).

**Scope**: these settings affect only the initial full load and baseline rebuilds; routine polling and CDC replay are unchanged. Global values live in `application.yml`. Batch size and parallelism can be overridden per task on the "Full-load performance" card (leave a field empty to inherit the global value); the other settings are global only.

### Measured baseline

Single container, 500,000-row Oracle table, batch 2000:

| Scenario | Configuration | Result |
|---|---|---|
| Empty-table write method | `MERGE` vs `INSERT` | ~84k rows/s vs **211k rows/s** |
| Per-table parallelism | 1 / 2 / 4 workers | 117k / 184k / **216k rows/s** |
| Secondary indexes | maintained during load vs after | 11.1s vs **5.9s + 0.5s** (about 47% faster) |

> These numbers show the direction of each knob, not a promise. Real throughput depends on source and target disks, network, driver versions, row width and concurrent load.

### Tuning procedure

1. **Establish a one-table baseline**: parallelism 1, batch 2000; record `rows/s` and the `read/write/commit` shares.
2. **Follow the largest share**: `commit%` → raise the batch from 2000 to 5000; `write%` → inspect target constraints, indexes and large values; `read%` → inspect the source cursor and scan width, then raise fetch to 5000.
3. **Then add parallelism**: confirm the pool is large enough (distinct configs `max-pool-size ≥ parallelism + 1`; one shared config `≥ 2 × parallelism + 2`), step from 2 to 4, change one variable at a time, and re-read the log; gains fall off clearly past 4.
4. **Confirm batching works**: `rowsPerBatch` should be close to the batch size. If it stays below half, the driver is not batching or a large value is splitting batches.
5. **Protect production**: on databases shared with live traffic set parallelism to 1 and keep batches at 1000–2000; in a dedicated migration window raise parallelism to 2–4 and keep chunks at 50k–200k.

---

<a name="schedule"></a>
## Scheduling

Scheduling decides how often increments are caught up. Jync has three layers: the global default, per-task overrides, and the long-lived consumer used by log CDC.

### Global default and per-task override

- **Global default**: `sync.poll-interval`, 2000 ms, in `application.yml`.
- **Per-task override**: enable "custom schedule" on the task page and enter an interval or a Cron expression; leave it empty to inherit the global default.
- A schedule is registered **after the task starts**. Stop a running task before changing its schedule or synchronized objects.

### Polling interval

- Milliseconds, clamped to **200ms – 24h** at runtime. Below 200ms is pathological polling; above a day, use Cron.
- Interval misfires are discarded: ten backlogged 2-second polls make no sense when the next cycle would see every change anyway.

### Cron

- Quartz syntax, **6 or 7 fields, seconds first**.
- It **takes precedence over the polling interval**. The expression is validated on save and on start; an invalid value is rejected.
- **Evaluated in the server time zone.** On a UTC server, `0 0 2 * * ?` fires at 10:00 local time — pin `-Duser.timezone` or the container `TZ` at deployment. The task page shows "next automatic run" together with its zone.
- A missed Cron trigger **fires once** when a worker is free, rather than silently skipping a whole day.

```text
0/2 * * * * ?        every 2 seconds
0 0/5 * * * ?        every 5 minutes
0 30 2 * * ?         daily at 02:30
0 0 8 ? * MON-FRI    weekdays at 08:00
```

### Start, catch-up and multiple instances

- **Immediate first run**: starting a task runs one cycle right away, then follows the schedule — no need to wait for the first interval.
- **Sync now**: catches up from the last successful position without changing the enabled state; a click during a running cycle merges into one catch-up rather than being dropped.
- **CDC tasks have no Quartz schedule**: the long-lived consumer replays continuously, so the interval, Cron and cursor column do not apply and the schedule card is hidden. Idle mining intervals are configured per engine (50ms minimum).
- **Multiple instances**: Quartz uses in-memory storage; each node rebuilds schedules from the enabled flags in the shared database on startup, and a reconciler aligns local schedules every 30 seconds. Actual execution of a task is serialized by a database lease.

---

<a name="cleansing"></a>
## Column cleansing

Column cleansing applies fixed rules to string columns **before rows are written to the target**. Source data is untouched. It handles whitespace, casing, prefixes/suffixes and formatting noise from legacy data — without turning Jync into a scripting engine.

### Rules

| Rule | Effect | Example |
|---|---|---|
| `TRIM` | Strip leading and trailing whitespace (Unicode) | `"  Zhang San "` → `"Zhang San"` |
| `UPPER` | Uppercase | `"ab-01"` → `"AB-01"` |
| `LOWER` | Lowercase | `"AB-01"` → `"ab-01"` |
| `BLANK_TO_NULL` | Empty or blank-only string becomes `NULL` | `"   "` → `NULL` |
| `STRIP_PREFIX` | Remove one fixed prefix | prefix `CN-`: `"CN-001"` → `"001"` |
| `STRIP_SUFFIX` | Remove one fixed suffix | suffix `.csv`: `"a.csv"` → `"a"` |
| `REGEX_REPLACE` | Replace every regex match | `\s+` → space; `^(\d{4})-` → `$1` |

### Order and scope

- Rules are configured per **table + column**; both names are **case-insensitive**.
- Rules on the same table run in **save order**: `TRIM` before `BLANK_TO_NULL` is what turns a blank-only value into `NULL`.
- **Only string values are processed.** Numbers and dates pass through unchanged; no implicit casts.
- The same rules apply to the full load, incremental polling and CDC replay; every parallel full-load worker applies them independently.
- A cleansed column is always materialized in full, bypassing the streaming large-value policy. A single value above `sync.lob.force-inline-max-bytes` (1MB by default) fails with an explicit message naming the table, column and setting.
- A running task must be stopped before its cleansing rules can be changed.

### Validation

- `STRIP_PREFIX`, `STRIP_SUFFIX` and `REGEX_REPLACE` require their argument.
- Regular expressions are validated on save using Java semantics: `$1` group references, `${name}` named groups and backslash escapes are supported. Invalid patterns and replacement strings are rejected immediately instead of failing mid-sync.
- An identical rule on the same column cannot be saved twice, so nothing runs twice.

---

<a name="databases"></a>
## Supported databases

**26 presets**: MySQL, MariaDB, Oracle, SQL Server, DB2, PostgreSQL, openGauss, **Huawei GaussDB**, **DM (达梦)**, **Kingbase (人大金仓)**, **OceanBase**, **TiDB**, **ZTE GoldenDB**, **Tencent TDSQL (MySQL edition)**, **Alibaba PolarDB (MySQL edition)**, **Wanli GreatDB**, **UnionPay UPDRDB**, **Transwarp KunDB**, **Ping An RASESQL**, **Dongfang Jinxin SeaboxSQL**, **HighGo**, **Vastbase**, **YashanDB**, **GBase**, **ShenTong Oscar**, H2.

- **Custom databases**: JDBC URL + driver class + driver jar path, loaded at runtime by an isolated `URLClassLoader`. Pick a dialect family (generic / Oracle / MySQL / PostgreSQL / SQL Server / DB2) to choose how DDL, upserts and metadata are handled.
- **Bundled drivers**: all except **GBase**, **ShenTong** and **Huawei GaussDB**, whose vendors do not publish official artifacts on Maven Central. Supply the jar and path; if a vendor's driver class differs from the preset, override it on the same card.
- **Huawei GaussDB** is a distinct product from openGauss: driver `com.huawei.gaussdb.jdbc.Driver`, URL prefix `jdbc:gaussdb://`, PostgreSQL dialect family.
- **Protocol-compatible presets**: GreatDB, UPDRDB and KunDB speak the MySQL protocol; RASESQL and SeaboxSQL speak the PostgreSQL protocol. They reuse the bundled drivers and dialect families, so tables, indexes, views, procedures and upserts follow the same verified path.
- **PolarDB / TDSQL editions**: the MySQL editions have dedicated presets; the PostgreSQL editions use the PostgreSQL preset. PolarDB Oracle-compatible edition speaks PostgreSQL but uses `com.aliyun.polardb2.Driver` — create it as a custom type.
- **Licenses**: driver versions and licenses are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

<a name="deploy"></a>
## Deployment

### Requirements

| Item | Requirement |
|---|---|
| JDK | **17+** |
| Maven | 3.6+ (source builds only) |
| Port | `8080` by default |
| Memory / disk | Heap ≥ 512MB recommended, 1GB reserved; scale the heap with the largest single large value |
| Working directory | Must be stable: `./data` (metadata store and key) and `./logs` are created relative to the process working directory |

The first start creates `./data` and `./logs` in the working directory. **Do not start from different directories** — if configuration seems to vanish, the process is pointing at another, empty metadata store.

### 1. Prepare the directory

```bash
sudo mkdir -p /opt/jync
sudo chown -R jync:jync /opt/jync
cd /opt/jync
```

Recommended layout:

```text
/opt/jync/
├── jync.jar
├── application.yml
├── data/          # H2 metadata store and <name>.key — must be backed up
└── logs/
```

### 2. Get the jar

Download `jync.jar` from the release pages:

- GitHub: <https://github.com/vfaner/jync/releases/latest>
- Gitee: <https://gitee.com/super_rgh/jync/releases/latest>
- AtomGit (mirror): <https://gitcode.com/vfaner/jync/releases/latest>

Build from source:

```bash
git clone https://github.com/vfaner/jync.git
cd jync
mvn clean package -DskipTests
cp target/jync.jar /opt/jync/
```

In mainland China, clone from Gitee: `git clone https://gitee.com/super_rgh/jync.git`, or the AtomGit mirror: `git clone https://gitcode.com/vfaner/jync.git`. Without `-DskipTests`, about 2,700 test cases run.

### 3. Production configuration

Create `application.yml` next to the jar:

```yaml
server:
  port: 8080

sync:
  poll-interval: 2000              # global polling interval (ms)
  batch-size: 500                  # rows per batch for incremental and CDC replay (live traffic: keep small)
  fetch-size: 1000                 # incremental result-set fetch size
  full-load-batch-size: 2000       # initial full load only: rows per batch (1-20000; 1000-5000 recommended)
  full-load-fetch-size: 2000       # initial full load only: source fetch size (1-20000)
  full-load-parallelism: 2         # full-load workers per table (1-8; 1-4 recommended; pool-capped)
  chunk-size: 100000               # rows per full-load chunk (50k-200k recommended; 0 disables chunking)
  initial-load-insert-first: true  # INSERT instead of upsert into an empty table
  defer-secondary-indexes: true    # build non-unique secondary indexes after the load
  max-pool-size: 5                 # per-database pool (5-10 recommended; must seat the workers)
  crypto-password: ""              # empty = a fresh store generates one into data/jync.key on first boot
  crypto-salt: ""                  # empty = generated too; set explicitly to manage the key yourself

  # ai:
  #   enabled: false               # isolated / air-gapped: disable AI assistance

app:
  login:
    # Emergency entrance: if local sign-in is disabled and external authentication
    # breaks, start once with this set to true (or JYN_RESET_LOGIN=true). It re-enables
    # local sign-in and saves that state; set it back to false after fixing the cause.
    reset-on-startup: false
  # isolated / air-gapped: disable version checks
  # update-check:
  #   enabled: false
```

**External metadata store (optional)**

To replace the H2 file store, point `spring.datasource.*` at an existing database before startup; `ddl-auto: update` creates the schema on first boot:

| Metadata store | Driver class | Example URL | Verification |
|---|---|---|---|
| H2 (default) | `org.h2.Driver` | `jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE` | Default path |
| MySQL 8 | `com.mysql.cj.jdbc.Driver` | `jdbc:mysql://db:3306/jync?useUnicode=true&characterEncoding=utf8` | Container contract test |
| PostgreSQL 16 | `org.postgresql.Driver` | `jdbc:postgresql://db:5432/jync` | Container contract test |
| Oracle 23 | `oracle.jdbc.OracleDriver` | `jdbc:oracle:thin:@//db:1521/FREEPDB1` | Container contract test |

```yaml
spring:
  datasource:
    url: "jdbc:mysql://db:3306/jync?useUnicode=true&characterEncoding=utf8"
    username: jync
    password: change-me
    driver-class-name: com.mysql.cj.jdbc.Driver   # omit when Spring Boot can infer it from the URL
```

The environment variables `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` and `SPRING_DATASOURCE_DRIVER_CLASS_NAME` override the same settings. Notes:

- **Create the database first**: for MySQL run `CREATE DATABASE jync DEFAULT CHARACTER SET utf8mb4;`; other types only need an empty database. The required JDBC drivers ship with the jar.
- **Set `SYNC_CRYPTO_PASSWORD` / `SYNC_CRYPTO_SALT` explicitly**: the generated key is tied to a local `<name>.key` file, which does not exist with an external metadata store. Without them, the public default key from the distribution is used.
- **Verified scope**: MySQL 8, PostgreSQL 16 and Oracle 23 have real-database contract tests (DDL, identity/auto-increment keys, `@Lob` round trips, batch update/delete, aggregate queries). SQL Server and the domestic databases have not been individually verified as metadata stores; PostgreSQL- and Oracle-compatible domestic databases (Kingbase, openGauss, HighGo, Vastbase, DM, YashanDB and others) are theoretically usable, but Hibernate dialect detection varies by driver — **validate a test boot with the same driver before production**.
- **Switching stores is not a migration**: an external metadata store starts empty; connections, tasks, cursors and checkpoints in the old H2 file are not carried over. Use it directly for a new deployment, or keep the old H2 store / migrate those rows yourself.
- **Requirement for multiple replicas**: H2 `AUTO_SERVER=TRUE` only shares within one host; multiple instances across hosts require one of the external stores above.

Start:

```bash
cd /opt/jync
java -Xms512m -Xmx2g -Duser.timezone=Asia/Shanghai \
  -jar jync.jar --spring.config.additional-location=file:./application.yml
```

> ⚠️ Use `--spring.config.additional-location` (an additive overlay). The replacing `spring.config.location` form drops the jar-internal configuration: the login page shows `??login.title_zh_CN??` and a fresh install creates no tables.
>
> 🕐 `-Duser.timezone` also decides the time zone used to evaluate Cron. If the server defaults to UTC, set it explicitly.

### 4. First sign-in

Open `http://<host>:8080`:

| Username | Role | Initial password |
|---|---|---|
| `admin` | Administrator | `123456` |
| `view` | Viewer | `123456` |

**The password must be changed after the first sign-in.** Until then only the change-password page is reachable; every other page and API is refused. Passwords are stored with BCrypt.

### 5. Register as a system service

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
sudo systemctl daemon-reload
sudo systemctl enable --now jync
sudo systemctl status jync
```

### 6. Docker and Compose

```bash
docker build -t jync:1.8.1 .
docker run -d --name jync -p 8080:8080 \
  -v "$PWD/data:/app/data" \
  -v "$PWD/logs:/app/logs" \
  -e TZ=Asia/Shanghai \
  --restart unless-stopped jync:1.8.1
```

Or with Compose:

```bash
docker compose up -d --build
```

- The runtime image contains only a JRE, runs as non-root, and ships a `HEALTHCHECK` on `/login`.
- Metadata and logs live on the host under `./data` and `./logs`, so rebuilding the image loses nothing.
- Environment variables, TLS, multiple instances and troubleshooting: [docs/deploy-container.md](docs/deploy-container.md).

### 7. Kubernetes

```bash
docker build -t registry.example.com/qqmu/jync:1.8.1 .
docker push registry.example.com/qqmu/jync:1.8.1
kubectl apply -k deploy/k8s/
kubectl -n jync get pods
```

- The manifests default to `jync:1.8.1`; change the image with the `images` transform in `kustomization.yaml`.
- The Service is `ClusterIP`. From outside the cluster use `kubectl -n jync port-forward svc/jync 8080:80` or configure an Ingress.
- The H2 metadata store is a `ReadWriteOnce` PVC, so **replicas must stay at 1**. For more replicas, move the metadata store to external MySQL or PostgreSQL first (`SPRING_DATASOURCE_*` environment variables), then raise `replicas`.

### 8. Reverse proxy and HTTPS

Nginx example:

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

Set `JYN_COOKIE_SECURE=true` for HTTPS. In gateway-header SSO mode, also add the gateway to `app.security.trusted-proxies`, and make sure the gateway strips client-supplied identity headers.

### 9. Upgrade and backup

1. Stop the service and back up `./data` (including `<name>.key`), `./logs` and `application.yml`.
2. Replace `jync.jar` and start again. The schema evolves under `ddl-auto: update`.
3. Changes made to the source while Jync was down are caught up by incremental sync or CDC after restart.
4. Administrators can also use the built-in version check and one-click upgrade on the "System info" page; the old jar is kept as `jync.jar.bak`, and the restart script replays the current JVM options and program arguments (JDWP debug agents excepted), so `-Xmx`, `-D` properties and `--spring.profiles.active` survive the upgrade.

> 🔐 Back up `<name>.key` and the metadata store together. Without the key, saved connection passwords and AI keys cannot be decrypted and must be re-entered.

### 10. Isolated and air-gapped networks

- `sync.ai.enabled=false` hides the AI menu and blocks AI drafting before any request is made.
- `app.update-check.enabled=false` stops GitHub / Gitee access and hides the upgrade entry.
- All static assets are local; no CDN or webfont is used.

---

<a name="config"></a>
## Configuration reference

Main settings in `application.yml` (the `sync.*` prefix):

| Key | Default | Description |
|---|---|---|
| `sync.poll-interval` | `2000` | Global polling interval (ms) |
| `sync.batch-size` | `500` | JDBC batch rows for incremental polling and CDC replay |
| `sync.fetch-size` | `1000` | Incremental result-set fetch size |
| `sync.full-load-batch-size` | `2000` | Initial full load only: rows per batch, 1–20000, 1000–5000 recommended |
| `sync.full-load-fetch-size` | `2000` | Initial full load only: source fetch size, 1–20000 |
| `sync.full-load-parallelism` | `2` | Full-load workers per table, 1–8, 1–4 recommended, further capped by the pool |
| `sync.chunk-size` | `100000` | Rows per full-load chunk, 50000–200000 recommended; `0` disables chunking |
| `sync.initial-load-insert-first` | `true` | Plain INSERT instead of upsert into an empty target |
| `sync.defer-secondary-indexes` | `true` | Build non-unique secondary indexes after the full load |
| `sync.max-pool-size` | `5` | Connection pool per database, 5–10 recommended; also caps parallelism |
| `sync.safety-lag-ms` | `1000` | Timestamp watermark pull-back |
| `sync.full-compare-max-rows` | `20000` | Row-count ceiling for *automatic* full-compare selection; an explicit task-level choice bypasses it, and the page plus the start-up warning will say so |
| `sync.row-count-audit-interval-ms` | `60000` | Row-count audit interval; `0` disables it |
| `sync.lock-ttl-ms` | `300000` | Sync lock lease duration |
| `sync.cdc.reconnect-backoff-ms` | `1000` | Initial CDC reconnect backoff |
| `sync.cdc.reconnect-backoff-max-ms` | `60000` | Maximum reconnect backoff |
| `sync.cdc.server-id-offset` | `612000` | MySQL fake-replica server-id base; actual id = offset + task ID |
| `sync.cdc.lock-ttl-ms` | `30000` | CDC consumer lease duration |
| `sync.cdc.supervise-interval-ms` | `10000` | How often each node checks and claims CDC leases |
| `sync.cdc.connect-timeout-ms` | `10000` | BinaryLogClient handshake timeout |
| `sync.cdc.logminer.batch-size` | `10000` | Rows per LogMiner read batch |
| `sync.cdc.logminer.idle-poll-ms` | `1000` | LogMiner sleep when the stream is truly idle |
| `sync.cdc.pg.idle-poll-ms` | `500` | PostgreSQL logical decoding idle sleep |
| `sync.cdc.mssql.idle-poll-ms` | `1000` | SQL Server CDC idle sleep |
| `sync.cdc.change-window-ms` | `10000` | CDC change-log aggregation window per table; `0` disables aggregation |
| `sync.lob.streaming-enabled` | `true` | Master switch for streaming large values |
| `sync.lob.inline-max-bytes` | `65536` | Large values up to this size stay in heap |
| `sync.crypto-password` / `sync.crypto-salt` | *(generated when empty)* | Connection-password encryption key; a fresh store generates one into `<name>.key` on first boot, while older stores start on the default key and migrate automatically |
| `sync.ai.enabled` | `true` | `false` removes the AI menu and endpoints |
| `app.update-check.enabled` | `true` | Version check; `false` makes no outbound request |
| `app.update-check.match-artifact` | `true` | When the version matches, compare the package too (GitHub digest / Gitee size); only a proven difference is reported |

> Connection passwords are encrypted with AES-256 and carry an `enc:` prefix so they cannot be double-encrypted. If the key is left empty it is generated on first boot; once set explicitly, no `<name>.key` is written.

---

<a name="auth"></a>
## Sign-in, roles and SSO

| Username | Role | Initial password | Permissions |
|---|---|---|---|
| `admin` | Administrator | `123456` | Everything |
| `view` | Viewer | `123456` | Read-only |

- **One authorization rule**: every POST requires an administrator. A viewer can read every page and all data, but has no write buttons — and a hand-built request is still refused.
- **Seeded accounts**: written on first start; skipped when the user table already has rows, so no one's password is reset.
- **Forgotten password**: there is no recovery. Delete the row from `app_user` and restart to re-seed.

**SSO** (Administration → External authentication) supports three modes, only one active at a time:

| Mode | Use case | Deployment requirement |
|---|---|---|
| Credentials | The portal exposes a login API | Jync can reach that API; form or JSON requests with field mapping |
| Gateway header | A gateway or reverse proxy authenticates and injects an identity header | Jync must sit behind the gateway; only the direct peer inside `app.security.trusted-proxies` is trusted |
| Ticket | A login center issues a one-time ticket | Configure the validation URL; only http(s), redirects are never followed |

- External users get a session only. They are not written to `app_user`, have no local password and no change-password entry.
- Local sign-in stays available at `/login/local` as the emergency entrance. Disable it only after external authentication is enabled and configured; once disabled, `POST /login` is refused as well.
- **Recover local sign-in**: start once with `JYN_RESET_LOGIN=true` (equivalent to `app.login.reset-on-startup: true` in `application.yml`). Jync re-enables local sign-in and saves that state; fix the external configuration, then remove the variable, set it back to `false` and restart.
- Embedding in an iframe requires HTTPS plus both `JYN_COOKIE_SECURE=true` and `JYN_COOKIE_SAME_SITE=None`.

---

<a name="workflow"></a>
## Workflow

1. **Connections** → create source and target connections → "Test connection" to confirm.
2. **Sync tasks** → create a task and pick the source and target.
3. **Task detail** → select tables, views and procedures; enter a target table name on the right when it differs (empty means the same name).
4. Configure **capture strategy, schedule, full-load performance and column cleansing** as needed.
5. Use **Sync now** for a trial cycle, or **Enable automatic sync** for continuous incremental sync.
6. Watch per-cycle detail in the **Change log** and overall state on the **Dashboard**. When an initial load finds rows the target already holds, the entry reads `50 row(s) inserted (53 scanned, 3 already up to date)` — scanned, written and already-current counts side by side, so it reconciles directly with the target's row count.
7. To confirm both sides hold the same data, open **Check row counts** in the task's per-table progress card: the audit runs only after you confirm, and marks each table consistent, mismatched, missing or timed out.

---

<a name="ai"></a>
## AI-assisted conversion (optional)

Procedural control flow in stored procedures cannot be translated reliably by text replacement. **AI settings** offer another path: the model **drafts candidates**, and a human reviews them before they are stored.

- **Four protocols**: OpenAI-compatible, Anthropic, Ollama (OpenAI-compatible endpoint) and Ollama (native `/api/chat`).
- **Hard boundary**: AI only produces candidates and **never participates in sync**; a candidate becomes a DDL override only after human confirmation. With no provider enabled, no outbound request is made.
- **Real probe**: saving sends a real request with `max_tokens: 1`, rather than only testing TCP connectivity.
- **Review page**: source definition and mechanical conversion side by side; the model's self-reported uncertainties are worth reading before the SQL.
- **Thinking levels**: follow the model, off, low or high. Anthropic receives `thinking.budget_tokens`; OpenAI-shaped providers receive `reasoning_effort`.

> ⚠️ Conversion sends procedure source to the endpoint you configure. On isolated or regulated networks, point it at a local inference endpoint (an Ollama preset is available when Ollama runs locally).

---

<a name="comparison"></a>
## Comparison with existing tools

> Basis: as of 2026-10, vendor documentation and the default deployment shape of each open-source tool, scoped to the "out-of-the-box continuous sync between heterogeneous databases" capability that overlaps this tool. Each product is stronger in its own domain; this table is not an overall assessment of any of them, and official documentation prevails where details differ. A fuller dimension-by-dimension comparison and the boundary cases are at <https://jync.qqmu.com/compare.html>.

| Dimension | **Jync** | Debezium + Kafka | DataX | Kettle | Navicat / DBeaver transfer |
|---|---|---|---|---|---|
| Deployment | **One jar** | Kafka + Connect + ZK | Client-side jobs | Desktop + repository | Desktop client |
| Configuration | **Web UI** | YAML / REST + consumer code | JSON jobs | Graphical ETL | Wizards |
| Time to first sync | **Minutes**: download, start, click | Days: stand up the Kafka stack | Hours: write JSON jobs | Days: learn the transform model | Minutes, one-off only |
| Continuous incremental | Polling / Cron + log CDC (MySQL binlog, Oracle LogMiner, PostgreSQL, SQL Server) | Log-based | Not applicable (one-shot) | Partial (self-built schedule) | Partial (scheduled jobs / full-table compare) |
| Full load | **Keyset chunks + in-table parallelism + streaming + checkpoints** | Initial / incremental snapshots supported; loading into a heterogeneous target is your consumer code | Sharded jobs | Implement inside the job | One-off wizard |
| Structure sync | **Tables, indexes, views, procedures created** | Partial; loading is your code | Pre-create tables | Partial | One-off only |
| Dialect conversion | **Types, functions, quoting, pagination, procedures** | No | Partial | Partial, manual | Partial |
| Per-task shaping and operations | **Column cleansing, target table renaming, interval / Cron, full-load batch and parallelism, source / target previews and on-demand row-count audit — all configured in the UI** | SMT or custom code | JSON jobs | Transform steps | Client-side preview only |
| Domestic databases | **DM, Kingbase, OceanBase and 10+ first-class presets** | Partial | Partial | Partial | Partial |
| Source intrusion | **Read-only by default**; CDC needs replication grants but is still read-only | Requires binlog / WAL | Read-only | Read-only | Read-only |
| Resume / idempotency | **Persisted cursor + primary-key upsert + checkpoint** | Offsets | Not applicable | Partial | Not applicable |
| Mobile / responsive | **Drawer navigation below 900px, role pickers and field mappings stacked below 640px, horizontally scrolling wide tables; check progress, start or stop tasks and make light edits from a phone browser** | Connector management UI; no mobile-adapted business UI | None (CLI) | Desktop client | Mostly desktop clients |
| Operations cost | **One process** | Streaming platform + consumers | External scheduler required | Repository + scheduler | Client scheduler / manual |

**Eight differences**

1. **One jar versus a streaming stack**: when the scale does not justify the operational cost, the difference is decisive.
2. **Minutes to first sync**: implementers follow a wizard instead of learning a streaming platform first.
3. **Structure and data together**: DDL, type mapping, indexes and procedure conversion are not your own scripts.
4. **Built for domestic databases and 信创 migrations**: dedicated dialects, not "generic JDBC might connect".
5. **Read-only polling by default**: one read-only account is enough; switch to log CDC when seconds matter.
6. **Full load designed for continuous sync too**: chunking, parallelism, streaming and checkpoints make the first load a resumable, observable engineering process.
7. **It works from a phone browser**: navigation collapses into a drawer, role pickers and field mappings stack on narrow screens, and wide tables scroll horizontally — check progress or start/stop a task without walking back to a desk.
8. **The migration chores need no code**: column cleansing, target table renaming, custom schedules, full-load batch and parallelism, source / target previews and on-demand row-count audit are all on the task page; the same cleansing rules apply to full load, incremental sync and CDC.

---

<a name="architecture"></a>
## Architecture and stack

**Stack**: Java 17 · Spring Boot 2.7 · Spring Data JPA / Hibernate · Quartz · H2 / MySQL / PostgreSQL metadata store · Thymeleaf server-side rendering · plain JavaScript (no CDN, no frontend build).

```text
com.qqmu.jync
├── config           i18n, Quartz, Jackson, SecurityConfig, SyncProperties
├── controller       MVC; controller/api for REST endpoints
├── service
│   ├── connection   DataSourceManager, DriverLoader, connection tests
│   ├── metadata     per-dialect metadata readers + snapshots
│   ├── monitor      ChangeDetector, CursorStrategyResolver
│   ├── converter    SqlDialect, type mapping, SQL body conversion
│   ├── sync         SyncEngine, DataSyncService, StructureSyncService, DdlExecutor
│   ├── cdc          binlog / LogMiner / PG / SQL Server consumers
│   ├── task         Quartz scheduling, three-layer locks, context assembly, startup recovery
│   ├── lob          streamed large-value reads, binding and spilling
│   └── ai           provider settings, shared HTTP layer, drafting and review
├── model / repository / dto / util
```

- **Three-layer locks**: Quartz `@DisallowConcurrentExecution` → an in-JVM `ReentrantLock` per task → a database lease (survives restarts and spans instances).
- **Transaction semantics**: cursor advancement and lock acquisition use `REQUIRES_NEW`, so they are not rolled back with the data writes.
- **Schedule reconciliation**: with multiple instances, each node aligns its local Quartz schedules with the enabled flags in the shared database.

---

<a name="docs"></a>
## Docs and tests

- **Container deployment and operations**: [docs/deploy-container.md](docs/deploy-container.md) — environment variables, TLS, multiple instances, upgrades and troubleshooting
- **Known limitations and boundaries**: [docs/known-limitations.md](docs/known-limitations.md) — CDC boundaries, keyless tables, large values, procedure conversion
- **Oracle → DM migration walkthrough**: [docs/migration-harness-oracle-to-dm.md](docs/migration-harness-oracle-to-dm.md)
- **Project article**: [docs/jync-article.md](docs/jync-article.md)
- **Release history**: [GitHub Releases](https://github.com/vfaner/jync/releases) · [Gitee Releases](https://gitee.com/super_rgh/jync/releases) · [AtomGit mirror](https://gitcode.com/vfaner/jync/releases)
- **Third-party driver licenses**: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)

```bash
mvn test
```

**2688 tests: 2687 pass, and 1 Linux-only key-file permission case is skipped on macOS.**

Coverage highlights:

- **Dialects and SQL**: idempotent upserts per dialect, type limits, SQL body rewriting that never touches literals or comments.
- **Full load and resume**: keyset chunking, disjoint parallel ranges, keyless OFFSET resume, checkpoints, column cleansing and primary-key boundaries.
- **Security**: password and AI-key encryption, every viewer POST refused, missing-CSRF refused, SSO mode isolation.
- **CDC**: prerequisite probes for all four engines, position discipline, lease exclusivity, failure summaries.
- **Pages and i18n**: every conditional branch renders without `??`, no Chinese leaks in the English UI, identical key sets in both bundles.
- **Upgrades**: GitHub → Gitee fallback, download verification, replacement, backup and rollback.

Real-database integration tests (`*IT`, excluded from the default suite, require Docker): Oracle 23ai parallel full load and `ROWDEPENDENCIES`; MySQL 8 binlog baseline → streaming, crash replay, 100k-row consistency, purge and disconnect failures. Run with `-Dapi.version=1.44` (Docker Engine ≥ 29).

---

<a name="contributing"></a>
## Contributing

| Channel | Account |
|---------|---------|
| QQ | [817094](https://wpa.qq.com/msgrd?v=3&uin=817094&site=qq&menu=yes) |
| QQ | [2912167928](https://wpa.qq.com/msgrd?v=3&uin=2912167928&site=qq&menu=yes) |
| QQ group | [426669837 · 沐沐开源工坊](https://qun.qq.com/universal-share/share?ac=1&authKey=xm%2BfgdraFGDpH8GHkV4DbuMjArg6BsPdNnXKUqcWvFXI1ocS56TcioSRYOVBvn5Q&busi_data=eyJncm91cENvZGUiOiI0MjY2Njk4MzciLCJ0b2tlbiI6Ind0V1pMYlVieXJkQmtnMVgveUFsOHFnbFhTd28wUE03dy9uVUErVExBdnlkMENnMis0UmhqWlJjcURzRHFnUEMiLCJ1aW4iOiI4MTcwOTQifQ%3D%3D&data=tZVIfZoO6N9QWSJE6ZbdZR15OmuLpSVucid5TicWYaMgb9Go1B975NZk0iZOo69AEzXQ4XucFtenImLltzFI5w&svctype=4&tempid=h5_group_info) |
| WeChat | qqmu66 |
| Email | [vfaner@88.com](mailto:vfaner@88.com) |
| Website | <https://jync.qqmu.com> |

Bug reports, feature requests and pull requests are welcome through any channel above. If Jync helps you, a **Star** ⭐ is appreciated.

---

<a name="license"></a>
## License

[MIT License](LICENSE), for commercial and non-commercial use. Bundled third-party JDBC drivers keep their vendors' licenses; see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). GBase, ShenTong and Huawei GaussDB drivers are not distributed with this project.

Maintained by 沐沐开源工坊: GitHub [@vfaner](https://github.com/vfaner), Gitee [@super_rgh](https://gitee.com/super_rgh), AtomGit [@vfaner](https://gitcode.com/vfaner). The code is free to use, modify and redistribute under MIT; the only request is not to replace the "Jync / 捷同" name, logo or author credit with your own — see [TRADEMARK.md](TRADEMARK.md).
