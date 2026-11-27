# Jync knowledge

## Author and contact

Jync is developed and maintained by 沐沐开源工坊 under the Apache License, 2.0. Official website:
https://jync.qqmu.com. Contact: QQ 817094 / 2912167928; WeChat qqmu66; QQ group 426669837;
email vfaner@88.com. The Jync name, logo and author attribution must not be replaced when
redistributing — see TRADEMARK.md in the repository.

## Documentation and manual downloads

- Website and online docs: https://jync.qqmu.com
- User guide (PDF): https://jync.qqmu.com/docs/jync-user-guide.pdf
- Deployment and operations guide (PDF): https://jync.qqmu.com/docs/jync-ops-guide.pdf
- Product overview (PDF): https://jync.qqmu.com/docs/jync-overview.pdf
- Deployment page: https://jync.qqmu.com/deploy.html
- Database capability map: https://jync.qqmu.com/database.html
The repository file docs/configuration.md is the authoritative list of configuration,
tuning and CDC prerequisites.

## Creating a sync task

Open Sync tasks → Real-time sync → New task. Pick a SOURCE connection and a TARGET connection,
save, then choose tables and a strategy on the detail page. Use a Redis cache target by choosing
the REDIS target kind.

## Dashboard at a glance

The Dashboard refreshes every 10 seconds: four cards (tasks and running, ETL tasks and enabled,
connections and synced tables, changes in 24 hours), host load/CPU/memory/disk gauges, a 24-hour
trend chart (sync or ETL), recent activity, and a project overview with table count, synced rows
and last sync time per task. Start here for overall health; per-task speed and failures live on
the task's Performance and Change log tabs.

- The "Live status · Updated HH:mm:ss" pill at the top writes the latest refresh time on every 10-second poll; the task card shows failing tasks as a red count beside the running count.
- Hover or keyboard-focus the four resource gauges (load / CPU / memory / disk) for used,
  available and percentage details; the CPU card also lists the processor model,
  physical/logical cores and OS, and "Top 5 CPU processes" expands a read-only list (refresh
  pauses while it is open). The gauges refresh every 5 seconds.

## Creating tasks at scale

- Table mapping: a source table can be written to another target table name; target names must be
  unique within one task. Mapping and table selection lock while running.
- Data preview: read-only sample rows from source or target; it never writes.
- Clone: copies an existing task's settings and deliberately leaves the target empty; pick another
  target before saving.
- Bulk create: reads the table list once and splits it by estimated size (catalog stats, optional
  exact COUNT(*)) into 1-50 stopped tasks; each task has its own pool, so starting many multiplies
  the load on both databases.

## The three capture strategies

- Full load: chunked, optionally parallel passes; checkpoints are resumable.
- Polling (POLLING): each cycle runs "cursor > last AND cursor <= new max" and upserts; the
  cursor never shows deletes.
- Log CDC: a long-running thread replays binlog / LogMiner / WAL / change tables / YStream,
  capturing INSERT, UPDATE and DELETE; it needs log retention, grants and supplemental
  logging.
Order: CDC if logging is possible; else polling with a last-modified column; else
full-table compare for small tables (sync.full-compare-max-rows=20000); else one full load.

## Cursor strategies explained

Auto-resolution (an explicit cursor wins):
1. TIMESTAMP: name matches UPDATE_TIME / UPDATED_AT / UPDT_TM / UPDATE_TM and type is
   temporal; sees inserts and updates. A composite or non-numeric primary key does not prevent
   a timestamp column from being selected. Creation abbreviations CREATE_TM / CRT_TM are used
   by the IDENTITY step below.
2. ROW_SCN: Oracle ORA_ROWSCN on a WITH ROWDEPENDENCIES table; no column, but a full scan
   each cycle.
3. IDENTITY: creation timestamp or single integral primary key; inserts only.
4. FULL_COMPARE: re-reads and upserts the whole table each cycle, under a row ceiling.
5. NONE: no safe cursor; one full load, with the reason shown.
Deletes: under sync.full-compare-max-rows (20000) both key sets are compared each cycle;
above that it is skipped.

## Pausing target foreign keys during a load

- "Pause target foreign keys during the load" is on by default. MySQL uses session-level `SET FOREIGN_KEY_CHECKS=0/1`; SQL Server uses per-table `ALTER TABLE ... NOCHECK` / `WITH CHECK CHECK CONSTRAINT ALL`, which is persistent table state.
- SQL Server records a marker before disabling. The data phase restores constraints in `finally`; a failed restore aborts the physical connection so NOCHECK cannot return to the pool. A killed process leaves the marker, and startup plus a 5-minute job restore it (unless the target connection is gone or another node holds the lease). The assistant cannot change this switch.

## Keyless tables

- Without a primary key there is no upsert: the first full load uses plain INSERT. A non-empty target (or one that cannot be proven empty) is refused; empty it or enable "truncate target before initial load" and retry.
- A keyless full load uses one streamed SELECT (statement-level snapshot), not OFFSET pages; concurrent writes cannot shift later pages, but it cannot resume after an interruption and the target must be proven empty. Add a primary key, or migrate through CDC/a read-only window.
- Log CDC can only seed a keyless table; later changes cannot address its target rows. Fan-out baselines skip keyless tables. FULL_COMPARE seeds once and then skips.

## How the progress card refreshes

- The task-detail table-progress card refreshes itself: about every 2 seconds while a full
  load runs, about every 5 seconds while the task is running (10 seconds above 200 tables).
  The strategy/cursor column, full-load state, incremental state, last sync time and
  full-load progress all update in place - no page reload. Polling pauses while the page is
  hidden and catches up when it comes back.
- The full-load progress column exists only while a full load is unfinished: loading now, or
  stopped mid-way with resumable checkpoints. It hides itself once every load completed, and
  shows "Loading..." before the first checkpoint is written.
- The strategy/cursor column shows the resolved strategy badge and cursor column. An em dash
  means the table has no progress row yet, or no usable cursor (incremental state LIMITED).
  A cursor resolved at the end of a full load appears on the next automatic refresh.
- The yellow index hint next to a cursor column comes from a source-side probe made when the
  page was loaded; after setting or changing a cursor column, reload the page to re-evaluate
  it. This is independent of the automatic status refresh.

## Target table / column name case

- Jync creates target objects with the source spelling, quoted (backticks on MySQL, double
  quotes on PostgreSQL / Oracle, brackets on SQL Server); it does not convert identifiers to
  upper or lower case.
- The stored case is decided by the target database's folding rules: MySQL's
  lower_case_table_names (fixed at initialisation; 1 / 2 stores table names lower-case), and
  openGauss / Vastbase-style lower_case_column_names (which lower-cases even quoted column
  names) plus their table-name setting. Jync cannot override this per session.
- Oracle-family targets (Oracle / Dameng / YashanDB) fold bare identifiers to upper case by default. When the source
  spelling (for example a lower-case MySQL `user_info`) must be kept, enable "Preserve source
  identifier case" in the project's sync options: Jync then creates columns and tables with
  double-quoted source spelling (`"user_info"`), and a mapped table name keeps the spelling it
  was given. The switch is shown for Oracle-family targets only and is off by default; existing upper-case target objects
  are not renamed, so recreate them and re-baseline, and application SQL must quote the same
  case. ETL tasks do not follow this switch.
- With the switch off, table names can still be renamed one by one with the task's table-name
  mapping; column names cannot be renamed — column rules transform values, not identifiers.
- Since 2.4.5 Jync reads the target catalog's actual column spellings for Vastbase-family
  targets, so syncing still works after folding; only the object names differ.
- To keep upper case on other targets, turn the folding parameter off in the target first
  (usually an initialisation or restart-time setting), then recreate the tables and resync.
  Objects already created in lower case are not renamed automatically.

## What a full load actually does

- A full load is not a comparison or a watermark probe: Jync reads the whole source table and
  upserts it into the target, recording the watermark as a side effect; later incremental
  cycles read only what changed after that watermark.
- When the target already holds data, it is not cleared by default: existing rows are updated
  and missing rows are filled. Rows that exist only on the target are not removed unless delete
  propagation is enabled and the delete comparison runs (within its row ceiling).
- Dialects with an "unchanged row" guard may report 0 rows for unchanged rows, but the source
  read and the statement still execute; this is not a free consistency check.
- There is no "set the watermark without copying" option; a baseline after a strategy switch
  always reads the table again.

## Why a strategy switch triggers another full load

- Log CDC (binlog / redo / WAL / change tables / YStream) and cursor polling are both
  incremental; they differ in where changes come from: the log, or a cursor-column query.
- Polling needs a cursor watermark to read only changes. After switching from log CDC to
  polling, or when a polling task is created, the table has no polling cursor, so the first
  cycle runs a full load to establish the watermark and later cycles are incremental. This is
  not a first sync in the data sense — it is a new baseline for the new strategy.
- That load upserts by primary key and does not clear the target by default (unless
  "truncate before initial load" is enabled): existing rows are refreshed and missing rows are
  filled, which usually repairs the gap that motivated the switch.
- Once the baseline finishes, no further full load runs unless the cursor is cleared or
  unreadable, the target table is recreated, or the strategy/cursor column changes again.
### Switching back from polling to log CDC

- Jync stops the consumer, clears the polling cursor and the old position, runs a new baseline
  and then streams from the new anchor; it never resumes the position from before the polling
  period.
- Before switching, verify:
  - The table is a base table with a primary key. Views have no row-change events and keyless
    tables cannot locate updates/deletes, so neither suits CDC (this is usually why data was
    missing during the binlog phase); keep them on polling.
  - If deletes matter, enable delete propagation first: the new baseline finishes with one
    delete comparison that removes rows deleted at the source during the polling period;
    otherwise they stay on the target.
  - Binlog / archive retention must exceed the baseline duration, or the anchor logs are
    purged and the switch immediately reports an expired position.
  - The switch runs another full load (primary-key upsert, target not cleared by default);
    schedule it off-peak for large tables.
- Order of operations: stop the task → confirm `binlog_format=ROW`, replication privileges and
  log retention → enable delete propagation if needed → switch the strategy → start → watch
  CDC lag and row counts.

## Real-time sync caveats

- Writes are key-based upserts (at-least-once): the target needs a primary or unique key.
- Baseline first, replay second; downtime changes are caught up from position or cursor.
- Source DDL does not alter the target schema; use schema sync. CDC trims missing columns
  and records a TODO instead of stopping.
- An expired position or cursor (purged binlog, archive or WAL) forces a baseline rebuild.
- A transaction is buffered up to 1,000,000 rows / 512MiB; beyond that the task parks.
- Changing the strategy, cursor column or table list re-initialises those tables.

## Tuning a full load

Useful sync.* settings (restart to apply):
- full-load-batch-size: 2000 (1000–5000). Rows per commit; larger means fewer commits, more
  rollback and memory.
- full-load-fetch-size: 2000 (1000–5000). Source result batch; lower it for wide fields.
- full-load-parallelism: 2 (1–4). Workers per table; 1→2 gains most.
- chunk-size: 100000 (50k–200k). Rows per chunk and the most a crash re-reads; 0 disables it.
A 10M-row table took about 40 seconds in our test (~250k rows/s). Row width, indexes, network,
target write capacity and concurrent load all affect the result.
Raise parallelism first, then batch; deferring secondary indexes usually still helps, but
measure it on your own environment.

## Reading the throughput log

Each table and worker prints one throughput line during a full load:
table=orders thread=full-load-orders rows=10000000 changed=10000000 elapsed=40.0s rows/s=250000.0 query=3% read=35% write=52% commit=10% batches=5000 rowsPerBatch=2000
- rows/s is that table's sync speed.
- High query%/read%: the source SQL, cursor or network is the bottleneck.
- High write%: target constraints, unique indexes, triggers or large fields are slow.
- High commit%: commits are too frequent; raise sync.full-load-batch-size.
Log file: ./logs/jync.log (UTF-8), 10MB per file, kept 30 days, 500MB total.

## Where to see sync speed and logs

- Task detail → Performance: throughput, lag and failure curves, sampled per minute, kept 7 days,
  with 1-hour and 24-hour ranges.
- Task detail → Change log: rows, cursor value and success per cycle; open the Detail column on failure.
- Log management → System log: the application log, same source as ./logs/jync.log.
- Database performance page: sessions, QPS, cache hit, lock waits and latency, sampled every 300s.
- Each polling cycle logs: Synced N row(s) (M changed) for table in window (...].
Setting metrics.enabled=false only stops sampling; sync is unaffected.

## Scheduling and poll interval

- Global sync.poll-interval is 2000 ms; a task may set its own interval or cron, otherwise it
  inherits the global value.
- Effective range is 200ms–24h; missed ticks are dropped because the next cycle sees everything.
- A task runs once at start, then follows its schedule; stop it before editing the schedule.
- "Sync now" keeps the state and catches up from the last success; "Stop sync" exits at the next
  committed batch boundary.
- Log CDC tasks have no Quartz schedule and hide the scheduling card.
- Instances rebuild the in-memory schedule; a database lease excludes concurrent runs.
- reconcile-interval-ms (default 30000, 30 s): how often instances re-check which one should
  run which task, so a failed node hands over within that window.

## Cron expressions

Sync tasks use Quartz syntax with 6 or 7 fields (year included); ETL and dedicated-migration tasks
use Spring's 6-field syntax (no year field), and a 7-field expression is refused on save. Seconds
come first, expressions are validated on save and at start, and a schedule overrides the poll
interval.
- 0/2 * * * * ?    every 2 seconds
- 0 0/5 * * * ?    every 5 minutes
- 0 30 2 * * ?     daily at 02:30
- 0 0 8 ? * MON-FRI    weekdays at 08:00
Evaluation uses the server time zone, so pin -Duser.timezone or the container TZ; a missed run
executes once when a worker becomes free.

## Incremental and CDC batching

- sync.batch-size: default 500, JDBC batch rows for incremental polling and CDC replay.
- sync.fetch-size: default 1000, result-set batch for incremental reads.
- sync.cdc.logminer.batch-size: default 10000, rows per LogMiner batch.
- Idle sleep: LogMiner and SQL Server default 1000ms, PostgreSQL 500ms, minimum 50ms; MySQL binlog
  is pushed by the server.
- sync.cdc.change-window-ms: default 10000, groups CDC change-log rows per table; 0 disables it.
- One source transaction is capped at 1,000,000 rows or 512MiB; exceeding it stops the consumer.

## Connection pool and parallelism

- sync.max-pool-size defaults to 5 per database connection pool.
- Full-load workers, incremental polling and CDC replay share one pool; keep the total parallelism
  within the pool size or the threads queue behind each other.
- When several tasks share a connection, running them at once competes for connections; stagger
  them or enlarge the pool before raising parallelism.
- Per-table parallelism splits an integral leading key arithmetically; other key shapes sample
  boundaries from the ordered key set, falling back to sequential streaming when unsupported.

## Large fields and indexes

- LOB, BLOB and JSON fields slow both reads and writes; lower fetch-size so a batch does not hold
  large values in memory.
- More secondary indexes on the target mean slower writes; create secondary indexes after the
  initial full load has finished.
- With a unique index on the target, the full load uses upsert instead of INSERT and runs at about
  1/2.5 of the INSERT throughput.
- Column cleaning forces the whole column to be materialized; a value above
  sync.lob.force-inline-max-bytes (default 1MB) fails with a clear error.

## LOB streaming and spill files

- Values up to sync.lob.inline-max-bytes (64KB) stay inline; larger ones stream unless
  streaming-enabled=false.
- CDC buffer values above the limit spill to sync.lob.spill-dir (jync-lob in the temp dir),
  deleted at transaction boundaries; spill-max-bytes caps one transaction at 1GiB and startup
  removes residue older than spill-retention-hours (default 24 hours).
- A batch is committed once it accumulates batch-max-bytes (default 16MiB); a single value above
  that budget gets a batch of its own.
- Primary key, cursor and cleaned columns must be inline; above force-inline-max-bytes (1MB) the
  task fails naming table and column.
- PostgreSQL/openGauss sources keep the read transaction open, delaying vacuum.

## Row counts and content audit

- Task detail → Check row count defaults to a fast estimate from catalog statistics (marked
  approximate). Exact checks run in a background job; the source and target share one total
  budget, `sync.row-count-job-timeout-seconds` (default 600 s), and a slow side is marked
  TIMEOUT independently. Stop cancels the live COUNT(*) statement. One exact batch is limited
  to 50 tables; use the estimate or re-check individual tables for more.
- Content audit samples 50 rows every 15 minutes by default; it only reports differences and never
  repairs the target, and requires a primary key, an incremental strategy and a finished full load.
- Settings: sync.content-audit-interval-ms (900000), sample-size (50), max-rows (2000000),
  max-tables-per-cycle (1), timeout-seconds (60).
- LOB, JSON and XML columns are excluded by default.
- Targets that cannot tell whether a row really changed (openGauss, HANA, Oscar and similar) count the target's affected rows during replay windows, so changed-row totals can be inflated; use CDC when exact change counts matter.

## Large columns and drift comparison (LONGTEXT / LONGBLOB)

- The precheck note "these target columns are excluded from drift comparison" does **not**
  mean the columns are unsupported. LONGTEXT / LONGBLOB, CLOB / BLOB, JSON / XML and similar
  large columns are read and written normally by full loads, polling and CDC; large values
  are streamed/spilled according to `sync.lob.streaming-enabled`, `sync.lob.inline-max-bytes`
  and the spill settings.
- The note only means they are excluded from **content-level drift comparison**: databases
  generally do not define a reliable `=` / `<>` for these types, so Jync cannot safely compare
  their contents column by column.
- This affects content audit, full comparison, and the upsert "row did not change, do not
  rewrite" guard. Row-count checks, primary-key matching and comparisons of ordinary columns
  are unaffected. If a table has any non-comparable column, replay rewrites the row instead
  of skipping it; the data is still correct.
- The precheck wording is capture-mode specific: CDC states that every change is written and
  does not recommend an updated-at column; full compare states that every cycle re-reads and
  writes these columns; only polling says an update limited to a large column may be missed
  and recommends a changing cursor column such as `updated_at`.
- In polling mode, an update that changes only a LOB column while the cursor column stays the
  same may not be detected by the drift audit. Use CDC (MySQL binlog, PG logical decoding,
  Oracle/Dameng LogMiner, SQL Server CDC, Yashan YStream) or add a changing cursor column such
  as `updated_at` to cover those updates.
- Byte/hash-level LOB comparison is not implemented. To detect updates that only change a
  large column, use CDC or add a changing cursor column such as `updated_at`; do not tell
  users hash comparison already exists.

## Reliability and timeout settings

- sync.safety-lag-ms (1000) rewinds the timestamp watermark so out-of-order commits are not
  skipped. The window's lower bound is strict `>`, so the effective rewind is one second larger
  than configured; this keeps a row exactly on the boundary from being missed.
- A numeric/identity cursor has no safety margin: it advances to the committed maximum, so a
  transaction that took a lower id before committing can fall outside the window. The row-count
  audit is the fallback and can lag; prefer a time cursor or log CDC when this matters.
- row-count-audit-interval-ms (60000) compares counts; gaps up to row-count-repair-max-rows (1000)
  are repaired by key, larger ones clear the cursor and reload the table.
- max-unindexed-scan-rows (1,000,000) stops COUNT(*) probing on a big table without a leading
  index on the cursor column, and warns.
- Timeouts: query 300s, copy 1800s, metadata 60s; pool-wait-ms 10000 yields to the next cycle.
- shutdown-wait-ms 20000; stuck-threshold-ms 30 min; lock-ttl-ms 5 min.

## Oracle CDC setup

1. Create a SOURCE connection of type Oracle.
2. Enter the mining user (minerUsername) and password; LogMiner privileges are required.
3. The source needs ARCHIVELOG and database-level SUPPLEMENTAL LOG DATA; each selected table needs
   (ALL) COLUMNS.
4. Switch to log CDC on the task detail page, select tables and start.
5. Oracle 11g+ (the mining-role check adapts to the server version).
6. In a CDB, DBMS_LOGMNR must run in CDB$ROOT and the mining user must read the target PDB's
   archives; keep at least 24 hours.
7. Out-of-line LOBs appear as placeholders: missing-lob-policy=skip warns and keeps the old target
   value (run a full sync to catch up); fail parks at the first one.

## MySQL CDC prerequisites

- MySQL 5.7+; log_bin=ON, binlog_format=ROW, binlog_row_image=FULL. If the source is switched to MINIMAL/NOBLOB while running, Jync parks at the row event and asks for FULL instead of skipping; zstd binlog_transaction_compression (MySQL 8.0.20+) is supported.
- The account needs REPLICATION SLAVE and REPLICATION CLIENT (named BINLOG MONITOR from MariaDB
  10.5 on).
- Any MySQL-wire kernel (MariaDB, TiDB, OceanBase, GBase, or a CUSTOM connection declared as MySQL)
  passes the family gate, but the connection test must decode one real binlog event to pass.
- Keep binlog for at least 24 hours; the position is binlog file + offset (including GTID).
- Each task uses server_id = sync.cdc.server-id-offset (default 612000) + project id; never let two
  instances consume the same project id against one source, or they will disconnect each other.

## Dameng CDC prerequisites

- DM8; ARCH_MODE=Y archive mode; RLOG_APPEND_LOGIC=2 (redo carries full before-images).
- The account can read V$ARCHIVED_LOG and execute DBMS_LOGMNR.
- Only archived files can be mined, so the archive switch interval is the latency floor; schedule
  ALTER SYSTEM SWITCH LOGFILE regularly.
- The position is the SCN from MAX(NEXT_CHANGE#).

## PostgreSQL logical decoding prerequisites

- PostgreSQL 11+, plus PG-family kernels such as openGauss, GaussDB, Kingbase, Highgo, Vastbase,
  Oscar, RaseSQL and SeaBoxSQL (a CUSTOM connection declared as POSTGRES goes through the same
  family gate and the live probe tests the slot for real).
- wal_level=logical; the account needs REPLICATION (openGauss does not require it); openGauss
  should set enable_slot_log=on.
- Every table's REPLICA IDENTITY must not be NOTHING.
- The position is the LSN; a replication slot retaining more than 4GiB of WAL raises a panel alert.

## SQL Server CDC prerequisites

- SQL Server 2012+; run sp_cdc_enable_db at database level AND sys.sp_cdc_enable_table per table
  (the consumer keeps polling until the capture instance appears; database-level alone captures
  nothing).
- SQL Server Agent must be running (the capture job runs under it); Azure SQL Database has no
  Agent and is not supported.
- The account must be in cdc_Admin / cdc_reader; without VIEW SERVER STATE the connection probe
  degrades to a warning instead of failing.
- The position is the LSN; the consumer polls every 1 second when idle.

## YashanDB YStream prerequisites

- YashanDB 23.4+; ARCHIVELOG mode and a connection to the primary; uses YStream, not LogMiner.
- The account needs CREATE SESSION, SELECT ANY TABLE, YSTREAM_CAPTURE and SELECT ANY DICTIONARY.
- Run ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS on every selected table; without it DML silently
  misses the stream (the table has no ALL-type group in DBA_LOG_GROUPS). Newly created tables are
  the usual gap; once the group exists the same insert appears in the stream immediately.
- Tables must be selected explicitly; each instance allows at most 32 YStream services.
- The position is a five-part tuple (commitScn / instanceId / groupLsn / groupOffset /
  batchRowId). Jync's own stored position is the authority: the server checkpoint only reflects
  its last periodic flush and must not decide where to resume.
- Rows rolled back to a savepoint never appear in the stream (the server filters them while
  parsing redo; 23.4.7 was measured not to emit savepoint LCRs). A row carrying a chunked LOB
  cannot be assembled and parks the project: exclude that table or switch it to polling.

## CDC positions, leases and multiple instances

- The flow is: record the position → chunked full-load baseline → long-running replay thread.
- Multiple instances use a database lease; after the lock holder dies, another node usually takes
  over within 60 seconds.
- An expired position (archives or binlog already purged) requires a new baseline.
- If a target table is dropped, Jync rebuilds and reloads only that table without moving the
  position; more than 3 consecutive repairs stop the consumer.
- A source DDL that leaves the target missing a column is trimmed in-stream and recorded as a todo;
  the task keeps running.
- Columns added after a capture instance was created are not in its `@captured_column_list`. Jync
  parks with a schema-drift error naming the missing columns and asks for the capture instance to
  be recreated; it no longer skips every change on that table.
- `Error retrieving record: Can not read response from server ... before connection was unexpectedly lost` comes from Connector/J streaming a query result, not from binlog decoding. The source connection was killed/restarted, a proxy/network timeout fired, or the target was slow enough to exceed MySQL `net_write_timeout`. Check the MySQL error log/`Aborted_clients`, raise `net_write_timeout`/proxy idle timeouts, or reduce batch size/parallelism. Replacing the jar only triggers it when the old process was still querying. Both chunked and single-stream full loads retry on a fresh connection after a disconnect: a keyed chunk keeps its committed part, a keyless single stream restarts the table. Add a primary key or use a sliceable plan for a huge keyless table.
- A skipped DDL is recorded as a todo (Change log → execute DDL on target); with "Auto-add nullable CDC columns" enabled, a single-target project automatically applies ALTER statements containing only ADD COLUMN where every new column is nullable and has no key/unique/identity/generated/reference clause. Even on success the task stays pending for "replay missing rows"; a gap replay requires the
project to be stopped. A failure also returns to the todo. Fan-out stays manual. DROP TABLE / DROP VIEW
  is stored with `IF EXISTS`; Oracle/DM/YashanDB strip the clause and treat "object already gone"
  as done, so an already-dropped target table no longer leaves the todo red forever.

## CDC stalls and rebuilding the baseline

- Rebuild when the position has expired (logs archived or purged), the consumer has been parked by
  repeated failures, or a target table was lost or damaged. Read task state and CDC runtime state
  first, then run the prerequisite self-check to rule out connectivity and privileges.
- A rebuild stops the consumer, deletes the position, clears every table cursor (the
  "initial load done" flag stays set, so the target is not truncated by default), lifts the error
  state, then restarts an enabled task with a full load. A disabled task is only reset; the next
  start runs the full load.
- The full load upserts by primary key: rows still present at the source are inserted or
  overwritten, and the target is not truncated first. Extra rows already on the target do not
  disappear because of a rebuild.
- To remove rows the source deleted while the stream was down, enable "sync deletes" before the
  rebuild; enabling it afterwards does not recover the missed deletes. It compares primary keys and
  only deletes, and under log CDC it runs once when the baseline load completes — later deletes are
  carried by the log stream itself. The table needs a primary key and must be within
  sync.full-compare-max-rows (default 20000). Above that, raise sync.delete-compare-max-rows to
  cover the row count (0 disables the paged comparison). A table whose primary key is rewritten by
  a column rule can only use the in-memory comparison.
- Still mismatched after a rebuild: report or summary tables produced outside Jync are never
  aligned by any baseline; also check target triggers, the delete-comparison ceiling and open
  source transactions.
- Multiple instances: run the rebuild on the node holding the consumer lease; other nodes refuse
  it by design. A full load of a large table is heavy, so avoid peak hours.

## External baseline adoption (skip the first full load)

- The entry lives on the project detail page and is admin-only. The task must be stopped, a single database target, and log CDC. Redis cache targets and one-source-many-target projects are not supported. The assistant can explain it but cannot execute it.
- **What the position is**: a point in the source's change stream (for MySQL, a binlog file name plus a byte offset) meaning "the target already contains every change up to here". Jync copies no rows and, after the task starts, replays only changes after that point; rows before it must already be in the target from your own dump/backup tool. It is not a row count, not a target-side position, and table progress is not backfilled.
- **Prerequisite**: take a consistent dump and record the source position at the same instant. Typical ways: `mysqldump --single-transaction --source-data=2` (older versions `--master-data=2`) writes the `CHANGE REPLICATION SOURCE` / `CHANGE MASTER` FILE/POS/GTID into the dump header; Percona XtraBackup records them in `xtrabackup_binlog_info`; or `FLUSH TABLES WITH READ LOCK` → `SHOW MASTER STATUS` → dump → `UNLOCK TABLES`. The target and the position must come from the same dump.
- **MySQL example**: file `mysql-bin.000123` (the File name from `SHOW BINARY LOGS`, not a path); position `4` (digits only, 4..the file's byte size; use 4 when the exact point is unknown; a position equal to the file size means the end of that file, so later events are in the next file and Jync continues there); GTID is optional, e.g. `3E11FA47-71CA-11E1-9E33-C80AA9429562:1-100`. Jync checks the GTID is a subset of the source's `@@GLOBAL.gtid_executed`; the file and position are still validated when GTID is supplied.
- If the exact point is unknown: pick a file you are sure is **older than the dump** and still listed by `SHOW BINARY LOGS`, and enter position `4` (the file start). Some changes already in the dump are replayed; primary-key upserts absorb them. Never pick a point later than the dump.
- **Common MySQL mistakes**: using `SHOW MASTER STATUS` after the dump (misses changes made during the dump); entering a row count, file size, or event number as the position; mixing a file and a position from different queries; using a binlog file that the source already purged (reported as file missing); using a GTID from a different instant than the file/position.
- PostgreSQL: use `SELECT pg_current_wal_lsn();` (for example `0/16B6C50`) or the slot's `confirmed_flush_lsn`. The project slot must exist before the dump, and the value cannot be earlier than `restart_lsn` / `confirmed_flush_lsn`; otherwise the dump and slot leave a gap and adoption is rejected.
- SQL Server: use `SELECT CONVERT(varchar(32), sys.fn_cdc_get_max_lsn(), 1)` (0x prefix, 22 characters). The selected tables must already have CDC capture instances, and the LSN cannot be later than the source's current maximum CDC LSN.
- Oracle / Dameng: use an SCN (Oracle `SELECT CURRENT_SCN FROM V$DATABASE`; Dameng queries its current SCN). It cannot be earlier than the oldest redo still mineable, nor later than the current SCN.
- Yashan: use the existing unique YStream service's `RESTART_POSITION`; it must match exactly.
- Adoption clears the old position, old full-load checkpoints and the ERROR hat; a validation or cleanup failure never marks the baseline done. After adoption `project_table_status` may legitimately show `NOT_STARTED`; do not treat it as "never synced". Use Rebuild baseline to reload. Confirm the target matches the position first: Jync performs no full-load verification.

## When data stops arriving

When the source has new or changed rows and the target shows nothing, split the question by task
type — the three kinds fail for different reasons.

- Real-time sync asks two things: did Jync see a change, and did it write one? If both the newest
  change and the newest successful write are stale, the cause is on the read side (task not running,
  CDC stalled, polling never triggered, a prerequisite on the position or timestamp column unmet).
  If changes are seen but the newest write failed, the cause is on the write side — read the failing
  side and error summary in the failure list. With CDC, also check whether the consumer is on this
  node and whether the position has expired; with polling, check that the cursor column actually
  changes at the source (an update that leaves the cursor column untouched is invisible to polling).
- ETL: confirm the task is enabled, its cron is valid and it is not running, then read the latest
  result line and the consecutive-failure count. Repeated successful runs that write 0 rows mean the
  run is healthy and the extraction window or resume watermark has no new data (the source really
  has none, or the range condition conflicts with the checkpoint) — not a sync fault.
- Dedicated migration: check whether the newest run is still RUNNING and whether the current index
  and written-document count are growing; INTERRUPTED means the process restarted mid-run; when
  failed indices are above zero, quote the summary in the run message.
- Ask the same question in the floating assistant and it gathers the evidence in one call —
  diagnose_project, diagnose_etl_task or diagnose_migration, by task type — then answers with a
  conclusion, recommendations and executable items. Start, stop, run-now and rebuild actions are
  confirmation cards and take effect only after Confirm. Each tool line goes through queued,
  running, done or failed; the self-check and row comparison, which open the source database,
  are not evidence until they finish.
- None of the three kinds sends the process log file to the AI; diagnosis uses task state, run
  history and product metadata only.

## ETL tasks and variables

ETL runs the customer's own SELECT and copies the rows. Built-in variables include `${date}`,
`${datetime}`, `${yesterday}`, `${month}`, `${year}`, `${taskName}`, `${targetTable}` and
`${runIndex}`; custom `name=value` variables can be added per task. Unknown variables are rejected
on save, precheck and run.

`${runIndex}` is the number of successful runs of the task: 0 on the first run, +1 per success,
unchanged by a failure (a retry reads the same value), and back to 0 after the extraction SQL or its
variables change. It suits a window that advances by run, e.g. `WHERE slot = ${runIndex}`.

## ETL run modes and resume

- Write modes: overwrite (clear and rewrite), append, and range overwrite (delete by a range
  condition, then write).
- A checkpoint is written after the load; a rerun resumes from it. Editing the SQL, variables or
  target table clears the checkpoint.
- A running task cannot be edited; precheck validates the SQL shape, target table and columns, and
  variable references.
- Tables with the same source and target can form a batch that shares connections and scheduling.
- An execution lease prevents the same ETL task from running twice concurrently.

## ETL caveats

- The customer's SELECT defines the target columns for a newly created table. For an existing target, Jync writes the intersection of result and target columns and warns about missing ones. Identity / GENERATED ALWAYS / computed columns are not written explicitly; using one as an UPSERT key fails.
- Overwrite commits the write-mode DELETE in the same transaction as the first batch, so a failed first batch rolls it back; an empty extraction commits the DELETE and empties the target. Append resumes from its resume key.
- Range overwrite deletes by range_condition; a resumed run skips the delete, a finished run
  clears the checkpoint.
- UPSERT updates rows matching upsert_key_columns; it replays every row, ignoring checkpoints.
- Editing the SQL, variables, target table, range condition, resume key or upsert keys clears
  it.
- A native MongoDB resume key must not mix BSON types or be all null.
- preSql runs after target creation and before the write-mode DELETE/extraction, committed immediately; it is skipped when resuming. APPEND keeps its checkpoint after success, so preSql normally runs only on the first run; OVERWRITE clears the checkpoint each run, so preSql runs every time. postSql runs after the load.
- Source and target must not be the same physical database; two connection rows with the same type/host/port/database/schema are refused (different schemas on one server are allowed).
- Target schema/table names are quoted by target dialect: backticks for MySQL, double quotes for PostgreSQL/Oracle/DM, brackets for SQL Server; Oracle family folds to upper case, PostgreSQL preserves the configured case.
- A native MongoDB source infers target columns from a sample; a later unseen field aborts the run instead of being dropped. Oracle precheck ORA-00942 is only a warning: it can mean missing or invisible to the account.

## Schema sync

Schema sync compares tables, indexes, views and routines, then shows the generated DDL for review
before applying. Cross-database runs map types, functions and identifier quoting by dialect family.

## Dedicated migration overview

Dedicated migration does not use JDBC and is independent of real-time sync and ETL; it does not
consume the JDBC pool.
- Three chains: Elasticsearch / OpenSearch → Easysearch; MongoDB → MongoDB; MongoDB → KingbaseES.
- It copies the whole index or collection as of the run, not continuously; rerunning OVERWRITE
  makes the target converge.
- It supports manual or cron runs, cancellation, and keeps the last 50 run records.

## Creating a dedicated migration

1. Sync tasks → Dedicated migration → Manage connections: for ES/OpenSearch/Easysearch enter the
   base URL and Basic or API Key auth; for MongoDB enter the connection string and database name.
2. Create a migration task, choose the type, and select indexes or collections (glob patterns are
   supported; system indexes are excluded by default).
3. Target naming: same as source, add a prefix, or add a suffix. Write mode: overwrite, append, or
   fail if it exists.
4. Run Precheck first, then save, then Run now or enable a cron schedule.

## Dedicated migration precheck and runs

- Precheck verifies source and target reachability and version compatibility, _source availability,
  mapping compatibility and existing target objects.
- A pattern that matches no index is a failure, never a 0/0 success.
- ES migration copies settings (cleaned), mappings and aliases, and writes documents by _id /
  _routing with _bulk.
- MongoDB → KingbaseES samples documents to infer columns and create tables; missing columns, type
  mismatches and a missing unique index on _id are reported by precheck.
- A RUNNING record left by a process restart is marked INTERRUPTED.

## Dedicated migration caveats

- A run is a snapshot, not a feed; rerun for later changes.
- At most 200 indices/collections per task; a glob matching nothing fails.
- ES → Easysearch copies cleaned settings, mappings and aliases; _source-disabled indices
  fail precheck. Batches: 1000 docs / 10MB.
- MongoDB → MongoDB: 1000-doc / 8MB batches; options, indexes and documents toggle separately.
- MongoDB → KingbaseES infers columns from 200 sampled docs, stores nested objects as JSON
  text, keys on _id; precheck reports missing columns and type mismatches.
- A RUNNING record left by a restart is INTERRUPTED and must be rerun.

## Redis cache target

- Choose REDIS as the project's target kind; Redis is a standalone target (standalone or Cluster,
  no Sentinel) and is not a DatabaseType.
- Only log CDC is supported; POLLING and FULL_COMPARE are rejected on save.
- Projection is configured per project: keyPrefix (default jync:<projectId>:), valueFormat (JSON or
  HASH), ttlSeconds, excludedColumns, maxValueBytes and maxCommandsPerTransaction.
- Semantics are baseline + replay + prefix sweep rebuild; oversized rows are skipped and counted,
  never truncated and never stopping the task.

## Column cleaning rules

- Runs before writing to the target; the source is untouched. Rules are per table and column and
  are case-insensitive.
- Rules run in saved order per table; full load, polling and CDC replay all use the same rules, and
  numbers and dates pass through unchanged.
- Common rules: TRIM, UPPER, LOWER, BLANK_TO_NULL.
- Rules that need an argument: STRIP_PREFIX, STRIP_SUFFIX, REGEX_REPLACE (Java regex, validated on save).

## Masking and hashing

- MASK_PHONE, MASK_ID_CARD, MASK_BANK_CARD, MASK_NAME and MASK_EMAIL preserve the format and
  roughly the length.
- HASH_SHA256 emits a fixed 64-character hex digest, deterministically, optionally salted; the salt
  only separates systems and does not resist dictionary attacks.
- A target column shorter than 64 characters fails on write.
- Masking and hashing only rewrite the target; a masked primary key, cursor or audit column can no
  longer be used to locate the source row.

## Connections and drivers

Drivers are bundled except GBase, ShenTong, GaussDB, SAP HANA, ClickHouse, Hive, Impala,
TDengine, Elasticsearch and MongoDB. For those, provide the vendor jar path in the connection
form; override the driver class on the same card when it differs.

## TDengine and time-series data

- WebSocket only (port 6041); supply the official `taos-jdbcdriver-3.10.0-dist.jar` (the `-dist`
  build bundles Netty and friends; the plain jar fails on missing classes). The connection must
  name a database.
- In real-time sync it is a SOURCE only, with no log CDC: polling or full comparison, so an
  update or delete inside a polling window is not visible as such. With "sync deletes" enabled
  and the table inside the row limit, the primary-key comparison still removes extra target rows.
- Keys and cursors: without a declared primary key `ts` is unique; a super table is located by
  `ts + declared key + TAG`, and subtables are not listed individually. Specify the cursor column
  by hand (usually `ts`); timestamp watermarks use `LAST(column)` (TDengine rejects `MAX`/`MIN`
  on TIMESTAMP), while integer and character cursors keep `MAX`.
- Writing to TDengine is ETL-only, and the target table must already exist (a normal table or a
  subtable) or be created by the pre-SQL. Jync never creates TDengine tables, a super table
  cannot be a target, and ETL does not support UPSERT there.
- A range-overwrite delete condition must carry a `ts` time window, otherwise TDengine rejects
  the whole delete (no row is written in that run); the plain overwrite delete works.
- Never use a `DECIMAL` column as an ETL target: server 3.3.6 with driver 3.10.0 / 3.8.4 sends
  the decimal as text and decodes it as binary, writing a wrong value without an error. Use
  `DOUBLE` (with precision loss) or a fixed server / driver combination.
- `BINARY` and `VARCHAR` are indistinguishable in the driver and read as text (non-UTF-8 bytes
  are corrupted); use `VARBINARY` for binary fidelity. `BIGINT UNSIGNED` maps to `DECIMAL(20,0)`
  and `GEOMETRY` to `VARBINARY`.
- No secondary indexes (indexes exist only on TAGs); when the cursor column is not the leading
  primary-key column every incremental read scans the whole table. No transactions, so a failed
  rerun may leave partial data.
- Baseline: server 3.3.6 community with driver 3.10.0; other combinations are unverified.

## Users, roles and permissions

- Built-in accounts: admin, ops and view; all start with 123456 and must change it at first login.
- admin has every resource and system setting; ops can view everything but writes only its own
  resources and cannot enter system settings, external auth, alerts, audit, AI settings or config
  packages; view is read-only.
- Every write is a POST requiring admin or ops; a viewer's constructed request is rejected.
- Ownership follows the creator; pre-upgrade resources have no owner, so ops can read but not
  reference them in new tasks.

## External authentication and SSO

Configuration → External authentication supports three modes; only one is active at a time:
- Username/password: the portal exposes a login API; Jync must reach it and maps form or JSON fields.
- Gateway header: the gateway authenticated and injects identity headers; Jync must sit behind it
  and only trusts direct peers in app.security.trusted-proxies.
- Ticket: the login center issues a one-time ticket; only http(s) is accepted, redirects are not
  followed.
External users keep only a session; they are not written to app_user and have no local password.

## Password recovery and local login

- A forgotten password cannot be recovered: delete the app_user row and restart to re-seed the
  built-in accounts.
- Local login stays available as an emergency entrance at /login/local; enable and configure external
  auth before disabling it.
- With local login disabled, POST /login is rejected too.
- To recover, start once with JYN_RESET_LOGIN=true (or app.login.reset-on-startup: true), fix the
  external configuration, then remove the flag and set it back to false.

## HTTPS via reverse proxy

- Jync listens on 8080 locally; Nginx, a gateway or a cloud balancer terminates TLS, so plaintext
  exists only between proxy and Jync.
- The proxy must forward Host, X-Forwarded-For and X-Forwarded-Proto.
- Set app.security.trusted-proxies (JYN_TRUSTED_PROXIES), or proxied requests count as plaintext.
- Enable app.security.require-https (JYN_REQUIRE_HTTPS=true) and JYN_COOKIE_SECURE=true.
- Configuration → System info should show the connection as HTTPS encrypted.
- Do not enable server.forward-headers-strategy=framework; clients could forge their address.

## HTTPS with in-app certificates

- Open Configuration → System settings → TLS certificate; choose PEM or Keystore (PKCS#12/JKS).
- PEM needs the server certificate first and an unencrypted PKCS#8/PKCS#1 key; Jync verifies the key
  matches the certificate.
- A keystore password must open both store and key; files are capped at 5MB, expired certificates
  are rejected.
- Set the HTTPS port (default 8443), optionally redirect HTTP, save, then restart.
- app.tls.keystore-path / app.tls.pem-cert-path or JYN_TLS_* can take over; the page turns read-only.
- If startup fails, JYN_TLS_ENABLED=false recovers the instance.

## Enforcing HTTPS and security headers

- app.security.require-https defaults to false; when enabled, requests judged non-TLS get 403.
- Two exceptions: a cookie-less GET /login health probe, and loopback when no HTTPS endpoint is
  available yet.
- Never enable it when 8080 is exposed directly and no certificate is uploaded, or everything except
  loopback gets 403.
- HTTPS responses carry HSTS; all responses carry CSP, Referrer-Policy, Permissions-Policy and
  X-Frame-Options.
- Embedding in an iframe needs HTTPS plus JYN_COOKIE_SECURE=true and JYN_COOKIE_SAME_SITE=None.

## Metadata database migration and config package

- Metadata defaults to H2; moving to MySQL / PostgreSQL / Oracle is more than a datasource change.
- Export a config package (optionally with progress and cursors), stop the old instance, set
  SPRING_DATASOURCE_* and SYNC_CRYPTO_* for the new database, start it, then import.
- Target config and state tables must be empty; users merge by name, keeping one administrator.
- The AES-256-GCM package needs an 8+ character password, is limited to 20MB, and carries the
  old keys — destroy it after import.
- Not included: change logs, audit, metrics, run history, checkpoints, TLS files.
- Import reallocates project ids and does NOT restore the "baseline done" flag, so a CDC project
  runs a full load again on first start. PG slot names contain only the project id and YStream
  service names carry a random suffix: stop the old instance's CDC and clean old slots/services
  before importing, or a new task can attach to a slot that is still running.

## Starting jync.jar or Docker with an external metadata database

Without `SPRING_DATASOURCE_*`, Jync uses embedded H2 at `./data/jync.mv.db`. MySQL, PostgreSQL and Oracle are supported for production metadata.

### Starting jync.jar

Create an empty database and account first. For MySQL:

```sql
CREATE DATABASE jync CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

Set the environment and run with JDK 17:

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://db:3306/jync?useSSL=true&serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME='jync'
export SPRING_DATASOURCE_PASSWORD='change-me'
export SPRING_DATASOURCE_DRIVER_CLASS_NAME='com.mysql.cj.jdbc.Driver'
export SYNC_CRYPTO_PASSWORD='a-long-random-secret'
export SYNC_CRYPTO_SALT='0123456789abcdef'  # even-length hex
java -jar jync.jar
```

`SYNC_CRYPTO_SALT` must be an even-length hexadecimal string; replace the sample before production. For PostgreSQL use `jdbc:postgresql://db:5432/jync` and `org.postgresql.Driver`; for Oracle use `jdbc:oracle:thin:@//db:1521/FREEPDB1` and `oracle.jdbc.OracleDriver`. Tables are created by `ddl-auto: update` in the empty database. Changing the URL alone does not migrate existing H2 data: export a config package from the old instance, start the new one, then import it. Set `SYNC_CRYPTO_PASSWORD/SALT` explicitly and keep them stable; keep `/app/data` mounted for TLS certificates and backups.

### Starting with Docker / Compose

The Jync image contains the application and JDBC drivers, not a MySQL/PostgreSQL/Oracle server. Ready-made examples live in:

```bash
docker compose -f deploy/docker-compose.mysql.yml up -d --build
# or docker-compose.postgres.yml / docker-compose.oracle.yml
```

They start the database and Jync together and point Jync at it. For an existing managed database, remove the database service and keep only Jync with the same environment variables. A direct `docker run` looks like:

```bash
docker run -d --name jync -p 8080:8080 \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://db:3306/jync?useSSL=true&serverTimezone=Asia/Shanghai' \
  -e SPRING_DATASOURCE_USERNAME=jync \
  -e SPRING_DATASOURCE_PASSWORD=change-me \
  -e SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver \
  -e SYNC_CRYPTO_PASSWORD=a-long-random-secret \
  -e SYNC_CRYPTO_SALT=0123456789abcdef \
  -v jync-data:/app/data -v jync-logs:/app/logs \
  vfaner/jync:latest
```

After editing Compose environment variables, run `docker compose up -d` to recreate the container; `restart` does not reread them. If the old instance has data, export a config package first and import it into the new instance; do not treat deleting the H2 file as a migration.

## Backup, upgrade and data directory

- Back up the whole data directory (default ./data, /app/data in containers) before upgrading; it
  holds the H2 metadata database and data/tls.
- Metadata tables evolve automatically through ddl-auto: update; no manual migration is needed.
- With Docker, pull the new tag and recreate the container; the data volume stays. In K8s, change
  newTag in the kustomization.
- Changes made on the source while Jync is down are caught up from the cursor or CDC position.
- Do not replace the bundled H2 version (fixed at 2.1.214) or an old database file may fail to open.

## Alerts

Alerts cover sync failures, stalled polling, idle projects, stopped CDC, drift, connection health,
PG slot backlog, skipped DDL (a CDC statement that could not be applied automatically) and a
repaired CDC target table. Generic JSON, DingTalk, WeCom and Feishu are supported, with cooldown and
recovery notices. Webhook URLs are stored encrypted. Webhooks pointing at localhost or private
networks are rejected by default (alert.webhook.allow-private=false); enable it for internal chat
services.

## Audit log

Logs → Audit log is append-only by default: no account can edit rows. An administrator may purge
by date (everything on or before the selected day; today's rows always survive, so there is no
clear-all), and the purge itself is recorded as a new audit event (actor, cutoff, count) — the
trim can never erase its own trace. It records login success and failure with source, project and
connection create/update/delete, force unlock, capture-strategy switches, CDC baseline rebuilds,
date purges of the change, audit and system logs, dedicated migration and ETL writes, config-package import/export,
diagnostics exports and AI assistant questions, each with time, user, action and detail. Filter by
action or user to reconstruct who changed what. Logs → Change log offers the same date-based
deletion scoped to the current filter (project / errors only); open DDL tasks are pending work and
are never deleted. Logs → System log lets administrators delete rolled archive files by date as
well; the live jync.log and .tmp intermediates are untouched, and that purge is recorded
in the audit trail too. Retention defaults: change log and
audit log 180 days each (sync.change-log-retention-days / sync.audit-retention-days, hourly
auto-prune, 0 disables); system log rolls with a 30-day history
(logging.logback.rollingpolicy.max-history, plus 10MB per file and a 500MB total cap).

## Diagnostics bundle

System settings → Diagnostics downloads a zip with the version, config shape, sync progress,
recent changes, audit and log tail, plus manifest.json listing each entry's size and SHA-256.
Passwords, encrypted payloads, credentialed URLs, cursor values and CDC layout signatures are
omitted; free text is redacted. The bundle is capped at 10MB (HTTP 413 beyond); it may still
contain business data, so treat it as sensitive. Attach it with reproduction steps and the task
name when asking for support.

## Prometheus metrics

- Set metrics.prometheus-token (env JYN_METRICS_TOKEN) to expose GET /api/metrics/prometheus; a
  blank token keeps the endpoint closed.
- Scrape with Authorization: Bearer <token>; this header is the only gate and no Jync session is
  needed.
- In-app sampling and the Prometheus endpoint are independent; disabling sampling does not affect
  sync.
- Without Prometheus, the task Performance page and the connection metrics page cover daily use.

## Update check and one-click upgrade

- The nav dot and System info check releases: GitHub first, Gitee (app.gitee-url) on failure.
- Success is cached 10 minutes, failure 1 minute; at an equal version the jar digest is compared,
  and a notice appears only when it differs.
- Offline: switch it off under System settings → Update check; the dot and prompts disappear. A
  value in the config file makes the switch read-only.
- One-click upgrade downloads the jar (split into 100MB parts from Gitee when needed), verifies and
  swaps it, keeping jync.jar.bak. Restart to finish; it interrupts running tasks, so use a quiet
  window.
- A container cannot swap the jar inside its image: pull the new image tag and recreate the
  container; the volume behind /app/data stays. runtime_info reports the version and deployment,
  but the assistant never performs the upgrade or restart.

## Troubleshooting sync issues

- Running but no progress: check the activity cycle and minutes since progress; over the threshold
  the task is flagged as stuck.
- Row counts differ: compare COUNT(*) on both sides, then check cursor monotonicity and open
  transactions.
- The lag panel is "time the newest applied change was produced at the source" versus "time this
  node processed it"; an idle source does not grow it. SQL Server CDC samples
  cdc.lsn_time_mapping by commit LSN and keeps the previous sample when the mapping row is gone.
- CDC lag grows or the stream stalls: check log retention, consumer position and network; for an
  expired position or a parked consumer see "CDC stalls and rebuilding the baseline".
- Row counts still differ after a rebuild: a rebuild does not delete rows; removing rows deleted
  at the source needs "sync deletes" plus the primary-key and row-count bounds; report tables
  produced outside Jync are not managed by any baseline.
- Slow full load: read the query/read/write/commit shares in the throughput log, then tune that side.
- Changes not arriving: confirm the chosen strategy and that the cursor column moves; for CDC check
  the prerequisites for the source engine.

## Troubleshooting connections and HTTPS

- Connection test fails: check port, grants and firewall; for vendor drivers check the jar path
  and driver class.
- HTTP 403 on pages: usually require-https is on while the request is still plaintext; check the
  proxy headers and trusted-proxies.
- HTTPS page unreachable after saving a certificate: the connector starts at boot, so restart Jync
  and open the HTTPS port in the firewall or container mapping.
- Login loop after enabling SSO: /login/local stays available; re-enable local login with
  JYN_RESET_LOGIN=true if the external configuration is broken.

## AI providers

Settings → AI providers holds model endpoints; one is enabled at a time and keys are encrypted
like database passwords. Uses: AI drafts for procedure conversion and the floating assistant.
- Protocols: OpenAI-compatible, Anthropic, Ollama compatible and native.
- Fields: base URL, model, max tokens (long procedures need room), timeout and reasoning (follow
  by default; local reasoning models may return no body). Max tokens and reasoning mainly govern
  procedure conversion; the operations assistant uses a fixed short reply budget and does not read
  this setting.
- Probes and drafts call your endpoint and send procedure source; prefer a local endpoint. With no
  provider there is no external request; sync.ai.enabled=false removes the feature.

## Procedure conversion and AI drafts

Conversion review lists a task's views, procedures and functions with a mechanical conversion
(types, functions, quoting) beside the original DDL.
- Save as override to replace the automatic result; overrides can be deleted.
- Draft with AI yields a candidate only: review, edit and save it; nothing reaches the target
  automatically, and a cached draft is reused.
- Check syntax on the target creates and drops a temporary object; acceptance does not prove
  behaviour, so test cursors, exceptions and NULL concatenation.
- Conversion never writes the target.

## What the AI operations assistant can do

- The floating assistant is for administrators and operators; it reads project state, progress rows,
  the last failure, and 1-hour throughput and lag.
- Evidence and execution order: live state/history/metrics/todos → call the tool first; product
  rules → search_docs; errors → search_logs; if docs and logs are insufficient →
  search_instance_code; only then use general experience, labeled as inference. For a requested
  change, call the matching control_*/update_* tool to produce a confirmation card instead of
  only pointing at the page.
- It reads one task's details (project_status): both connection names and types, capture strategy,
  selected-table count, sync data/structure/delete switches and next scheduled run.
- It reads per-table sync state inside a task (project_table_status), including tables that never
  started; see "Task and table status questions" below.
- Answers follow "conclusion → evidence → recommendations → executable items"; a runtime action
  becomes a confirmation card, one per option, effective only after Confirm. Actions it cannot
  perform (editing configuration, dropping a table, rerunning an external report job) are written
  as manual steps, never as cards.
- When the user describes a sync-task symptom (stall, mismatch, repeated failures, slowdown), one
  diagnose_project call gathers task state, recent failures, active alerts, the CDC position, the
  rebuild-relevant switches (captureStrategy, syncDeletes, truncateBeforeInitialLoad,
  fullCompareMaxRows, deleteCompareMaxRows) and the newest change / newest successful write times —
  separating "never saw a change" (read side) from "saw it and the write failed" (write side).
- It searches this installation's own application log (`search_logs`) by keyword, level and
  rolled file — use it to explain an error stack or a failure.
- It searches the deployed artifact (`search_instance_code`) for configuration and code
  constants: application.yml, i18n, AI docs, templates, static JS and com.qqmu.jync class
  constants (error messages/SQL/property names). It does not read business data and the jar
  contains no .java sources.
- It reads the open CDC DDL tasks of one project (`project_ddl_tasks`, up to 20): task id,
  table, status (PENDING/FAILED), executed/replayed flags, target SQL, error and affected tables —
  use it when the target is missing a column or a replay is still outstanding.
- It reads change-log (CDC) runtime state: connection, position, last error, skipped DDL and
  pruned-column counters, and whether the consumer runs on this node or elsewhere.
- It can run the change-log prerequisite self-check for one project (archivelog, binlog format and
  retention, supplemental logging, replication or mining privileges, server-id collisions) and list
  every failing item. It opens the source database, runs at most twice per conversation, and needs
  edit rights on the project — the same right as the page's self-check button.
- It lists the alert conditions active right now (sync failure, stuck polling, CDC stopped, drift,
  unhealthy connection, PG slot backlog, skipped DDL) and ETL task state: enabled, last run time,
  success and result line.
- It diagnoses one ETL task (diagnose_etl_task): enabled, cron validity, in-flight state, both
  connections, the latest result and the last 20 runs with row counts and timestamps — separating
  "running fine with no new data" from "failing all night". A non-null batchId marks a batch member
  sharing connections and scheduling.
- It lists dedicated-migration tasks (list_migrations) and diagnoses one (diagnose_migration): type,
  both connections, enabled state, latest run status, current index, documents read and written, and
  failed-index count.
- It reads failure history over the last 1 to 168 hours (default 24, at most 20 rows), optionally
  for one project — including short failures from last night that had already recovered, which the
  alert board no longer shows.
- Suggested order: alerts and recent failures, task status, CDC runtime state, the prerequisite
  self-check, then this knowledge base for prerequisites and limits. For a mismatch, check
  syncDeletes, the comparison limits, and whether the target comes from another job.
- It reads connection database metrics (1h / 24h), lists connections and projects, and compares the
  exact row count of one table on both sides.
- It searches this knowledge base for how-to answers and drafts a new task from a description, which
  the user must review before it pre-fills the form.
- It reads the effective retention windows for change-log, audit, system-log and metrics data
  (log_retention), so "how long are logs kept" and "why did old entries disappear" are answered
  from the running configuration; deleting logs itself stays a manual page action.
- The running version, newest published version and update state, plus whether this instance runs
  in a container (runtime_info), so "am I on the latest version", "how do I upgrade" and "Docker
  or jar" are answered from the installation; upgrading, pulling images and restarting remain
  manual actions.

## Assistant actions and confirmation cards

- Runtime actions it can perform: start / stop a sync task, sync once now, rebuild the CDC
  baseline, enable / disable / run an ETL task now, and enable / disable / run / cancel a
  dedicated-migration task. The assistant only produces a confirmation card and never executes
  directly; when you click "Confirm" the server checks the acting account's role and resource
  ownership again — an operator can only act on resources they own, an administrator on anything,
  and viewers never see the assistant. A baseline rebuild triggers a full load and, with multiple
  instances, must run on the node holding the consumer lease. Preconditions are checked before a
  card is shown: a migration task without a usable cron cannot be enabled, one already running
  cannot be started again, and a task with no run in flight cannot be cancelled.
- Runtime tuning it can prepare (update_project_params): batch size (1-20000), full-load
  parallelism (1-8), poll interval (at least 200 ms; polling / full-compare only) and CDC idle
  poll (at least 50 ms; LogMiner, PostgreSQL logical, SQL Server CDC or YStream only). The task
  must be stopped; while it is running the assistant prepares a Stop card first. Cards are only
  prepared for tasks the caller may edit (administrators may edit anything). Each card lists the
  old and new values; nothing is written until Confirm, the change takes effect on the next start,
  and the audit row carries the field names and new values.
- Administrators can read and override ten system performance parameters
  (system_params / update_system_params): batch, fetch, chunk sizing and retry count, all with
  the sync. prefix, as strings; set overrides, reset restores the application.yml base. The change
  applies to subsequent sync rounds and converges across nodes in about 30 seconds; administrator
  only. Reset restores each node's own application.yml value, so keep those bases consistent
  across a cluster. Pool sizes, timeouts, threads, retention, comparison/delete semantics, CDC transaction
  limits and TLS/authentication/keys remain closed.
- Actions have a boundary: deletes, configuration changes beyond the fields above, connection
  identity fields, system settings, external authentication, alerts, AI settings, users and audit
  are outside the assistant's reach, and it cannot run SQL. Table selection, capture strategy,
  sync deletes/truncate, column cleansing/masking, DDL overrides and application.yml properties
  (pool, chunk, timeouts, max-concurrent-tables) still cannot be changed through the assistant;
  those are advice plus the property name and a restart requirement. A change-log (CDC) project cannot "sync now"; it can only rebuild its
  baseline, and the assistant says so instead of offering a card that is bound to be refused.

## Task, schedule and connection tuning

- Batch cursor columns and priority (update_project_cursor): columns maps table to cursor column
  (blank returns to auto-detection); priority is an ordered list, at most 20. Polling projects
  only — log CDC, full compare and Redis targets do not use cursor columns. A table whose effective
  cursor changes is re-loaded on the next cycle, and the task must be stopped. One card carries
  at most 20 changes; split a larger batch.
- Cron (update_project_schedule): a cron takes precedence over the poll interval; an empty value
  clears it and returns to interval scheduling. Polling / full-compare only — log CDC and Redis
  targets have no Quartz schedule. Cron is interpreted in the server JVM's time zone.
- Connection driver parameters (update_connection_params): inspect current values and dependent
  tasks with connection_details first. Only extraParams can be changed (connectTimeout,
  socketTimeout, batching and keep-alive flags, etc.; set adds/updates, remove deletes, at most
  20 changes in total); host, port, database, account, password, custom URL, driver jar and
  connection type stay on the page. A connection may be shared: the caller must be able to edit
  it and every task using it must be stopped; if another account's task also uses it, only an
  administrator may change it through the assistant. The card lists old and new values plus
  affected tasks.

## Assistant limits and data boundary

- Tool results are a snapshot taken at query time, not a live feed: answers carry the time or the
  minutes since then, and a fresh call is needed for a newer state. The prerequisite self-check
  runs at most twice and the row comparison at most three times per conversation; on reaching the
  cap the assistant says so and points to the page or a new conversation.
- Every number must come from a tool result and is never made up; when a tool fails, explain why
  instead of retrying the same call.

## Task and table status questions

- "Show me this task's details" is project_status: both connection names and types, capture
  strategy, selected-table count, sync data/structure/delete switches, next scheduled run, running
  state, current cycle, last failure and 1-hour metrics.
- "Has table X finished?" is project_table_status. States: COMPLETED (initial full load done,
  incremental sync continues), IN_PROGRESS (full-load checkpoints exist; loading or resumable),
  INCOMPLETE (progress exists but the initial load never finished), NOT_STARTED (selected, no
  progress yet). Each row carries an incrementalState (RUNNING / PAUSED / ERROR / LIMITED /
  WAITING, inferred from the project state and the table's cursor capability, not a per-table CDC
  catch-up proof), cumulative changed rows, last sync time, rows copied by the current full load
  and the checkpoint update time.
- Filter by table-name substring (case-insensitive) or by state; large projects return per-state
  counts first, and a capped list is marked truncated — report the total, do not present the
  sample as complete.
- A running task does not mean every table finished. Never-started tables have no progress row,
  so table state cannot be inferred from project_status progress or from the task's running flag.
- selectionKnown=false marks a legacy config with no explicit table list: the summary is based on
  existing progress rows only, so never-started tables cannot be listed — say so.
- These are metadata snapshots taken at query time, not live counters; when answering, quote the
  time or minute count from the result.

## ClickHouse as a source

- Source only, no log CDC: real-time sync uses polling or full comparison; with sync deletes on and
  the table within the row ceiling, extra target rows are removed by primary-key comparison.
- The driver is not bundled (supply `clickhouse-jdbc`). Keyless tables load through LIMIT/OFFSET
  paging, so a retry can duplicate rows — declare a primary key or use a unique cursor column.
- Prefer a DateTime64 or numeric cursor column; temporal watermarks compare as wall-clock literals
  and no longer depend on the server/JVM zone (fixed in 2.4.4).
- Auto-created targets map String → TEXT, Decimal(p,s) → NUMERIC(p,s), DateTime64 → TIMESTAMP and
  Nullable → a nullable column.

## Running multiple instances

- Cross-host replicas need an external metadata database; H2 files only share within one host.
- Set the same SYNC_CRYPTO_PASSWORD / SYNC_CRYPTO_SALT on every node, or each generates its own key.
- Quartz is in-memory and rebuilds schedules from the database; a database lease excludes
  concurrent CDC and sync runs.
- In-app TLS settings are node-local; prefer Ingress TLS for replicas, or configuration-file mode.
- Two instances on one source compute the same MySQL server_id for the same project id; stop CDC
  on demo instances first, or change sync.cdc.server-id-offset.

## Reading an error log

Users can paste a complete error into the AI assistant; there is no need to trim it first. Long messages keep both the head and the tail; an omission marker means the middle was cut, so ask for that section separately. Start with the first ERROR line and its `Caused by`, and do not treat the SQL echoed by Jync as the root cause.

- `Batch write failed ... Column "X" not found in upsert attrno state initialization`: a quoted identifier's case differs from the Vastbase/openGauss catalog spelling. 2.4.5 generates SQL from the target catalog's spelling; after upgrading, check whether `lower_case_column_names` stored the columns in another case.
- `UPDATE with where condition is not yet supported within INSERT ON CONFLICT`: Vastbase rejects the unchanged-row guard. 2.4.5 writes the native statement without it and emulates guarded incremental windows with UPDATE + INSERT.
- `unsupported literal: TIME'...'`: a Dameng LogMiner ANSI TIME literal. 2.4.5 supports it; upgrade and restart CDC.
- `Skipping INSERT/UPDATE/DELETE row on schema.table: <reason>`: CDC deliberately skipped the row, so the target may miss that change. Act on the reason; do not retry blindly.
- `OUT_CLOB` / `OUT_BLOB` or "missing LOB" on the panel: Dameng redo has no out-of-row LOB content. Set `LOGMNR_PARSE_LOB=1` and `LOB_MAX_INROW_LEN=8000` for values up to 8 KB; larger values need polling or a periodic full load.
- "No-PK tables, baseline seeding only": the source really has no primary key, or metadata read the table under a different case and missed it. Check the key definition and the schema/table spelling; a genuinely keyless table is only seeded once.
- Source `DATETIME` becoming `DATETIME(6)`: MySQL-to-MySQL type mapping. 2.4.5 preserves the source precision; existing target columns are not altered automatically.

## Dameng LogMiner skipped events and LOBs

"Unsupported events skipped" is the total, and it includes "missing LOB changes"; do not add the two counters. For example, 5875 and 2035 means 2035 are LOB rows and about 3840 have other reasons.

- Read `Skipping <OP> row on <schema>.<table>: <reason>`. `unsupported literal` is an unparsed literal; `column ... not in binding` is redo/catalog mismatch; `where missing PK column` means the WHERE clause carried no primary key.
- The LOB table list shows per-table counts. With `LOGMNR_PARSE_LOB=0` (default), CLOB/BLOB appear as `OUT_CLOB`/`OUT_BLOB`. `CALL SP_SET_PARA_VALUE(1,'LOGMNR_PARSE_LOB',1)` plus `LOB_MAX_INROW_LEN=8000` (restart if needed) makes values up to 8 KB minable.
- LOB content above 8 KB or stored out of row is not in the archived redo; standard LogMiner cannot recover it. Split those tables into polling/ETL tasks, or move large LOBs to separate tables and keep a reference.
- `sync.cdc.logminer.missing-lob-policy=skip` keeps the old target value and counts the skip; `fail` stops at the first missing LOB.
- A LogMiner `PURGED` (ORA-01291) detail includes the resume SCN and oldest still-available redo. If the oldest is EARLIER than resume but a file is still missing, it is an archive gap/deleted file/thread/resetlogs issue — raising retention will not help; check `V$ARCHIVED_LOG` continuity. If the oldest is LATER than resume, retention is too short. Rows with `DELETED=YES` are no longer selected or counted in the floor.

## MySQL auto-increment synced to Dameng

- On DM8, an **integer auto-increment column that is part of the primary key** uses native `AUTO_INCREMENT` (verified on 8.1.2.128). The target dictionary shows auto-increment; explicit source IDs are written as-is without `SET IDENTITY_INSERT`; the counter follows the largest explicit value (including multi-row INSERT and MERGE), so no post-load reseed is needed. An explicit 0 is treated as "generate a value", matching MySQL's default.
- Non-key auto-increment columns, or non-integer types (for example NUMBER identity from an Oracle-family source), keep the `SEQ_<table>_<column>` + `TRG_<table>_<column>` trigger emulation; not showing "auto-increment" in the dictionary is then expected. Existing tables are not converted automatically; recreate them and re-baseline to get the native attribute.
- DM rejects modifying an `AUTO_INCREMENT` column (`attempt to modify auto-increment column`, error -2664), so a type change on such a column fails and stays in the change log; recreate the target table (or change it by a target-side method DM allows) instead of retrying MODIFY. Do not recommend native DM IDENTITY: explicit ID inserts are rejected.
- If the SEQ/TRG objects are missing, check whether Sync structure is enabled, whether the target table was pre-created with structure sync off, whether the MySQL AUTO_INCREMENT flag was read from metadata, and whether this is an old baseline. Rebuild the baseline to reload.
- DM folds bare identifiers to upper case by default. If the source (e.g. MySQL lower-case) and application SQL rely on the same spelling, enable "Preserve source identifier case" under the project's sync options: Jync then creates and writes objects with the source/mapped spelling in double quotes, and application queries must quote the same case. The switch is shown for Oracle-family targets only and is off by default, and does not rename existing upper-case objects — recreate the target tables and re-baseline. Do not suggest changing a global DM parameter to lowercase.

## LogMiner ORA-01291 keeps failing after rebuilds

- Do not blame retention first: three days of archives with a baseline that takes minutes means retention is not the cause. `ORA-01291: missing logfile` means an archive covering the resume SCN is absent or inaccessible.
- If the deployed build selects only `DEST_ID = 1` while the local archive copy is on `DEST_ID = 2` (destination 1 is standby/remote), Jync cannot see it and reports a missing file. The current implementation reads all destinations, deduplicates by thread+sequence and prefers the local copy; still verify the files exist after deploying such a build.
- Check `V$ARCHIVED_LOG`: `DEST_ID`, `DELETED`, `STATUS`, sequence continuity and whether each `NEXT_CHANGE#` equals the next `FIRST_CHANGE#`; also require a row with `FIRST_CHANGE# <= resume AND NEXT_CHANGE# > resume`. If none exists, the anchor is not covered (archive gap or resetlogs/incarnation); rebuilding cannot recover it. Every RAC thread must be continuous.
- The current implementation checks whether the anchor is covered by an archived log or CURRENT redo before marking the baseline done, so a source gap fails early with a clear message instead of a later ORA-01291.
- Fix by restoring missing archives, or after confirming they are unnecessary run `CROSSCHECK` + `DELETE EXPIRED` to clear stale control-file rows, then rebuild. Logs that are truly gone cannot be recovered incrementally; re-run a full baseline and keep enough retention afterwards.
- `LogMiner cannot add archive file '<path>' (missing, deleted or not locally accessible on the source)` names the exact file Jync tried to add. Check that path's `DEST_ID`/`STANDBY_DEST` (standby/remote copies are unreadable), `DELETED`/`STATUS` and local existence; a continuous SCN list in `V$ARCHIVED_LOG` is not sufficient if the selected destination is remote.

## Target identifier case and type precision

- On Vastbase and other PG-family targets with `lower_case_column_names`, quoted columns may be stored lower-case. 2.4.5 reads the target catalog's actual spelling before writing.
- A lower-case Dameng table previously could be read with a forced upper-case name, appearing to have no columns or primary key. 2.4.5 reads the event's spelling first and falls back to upper case.
- MySQL-to-MySQL keeps `DATETIME`/`TIME` precision and the original `TEXT`/`BLOB`/`JSON`/`BINARY` types; cross-family sources still widen to avoid truncation.
- These rules apply to newly created targets only; existing columns are not altered automatically.

## Docker deployment and ARM64

- Official images support **linux/amd64 (x86_64)** and **linux/arm64 (aarch64)**. `aarch64` is ARM64, not a third architecture; Docker calls it `arm64`, while 32-bit ARM is `arm/v7`.
- On a machine where `uname -m` prints `aarch64`, run `docker pull vfaner/jync:latest` and Docker selects the arm64 image automatically. Do not force `--platform linux/amd64`; that uses QEMU and can produce `exec format error` or severe slowdown.
- Kylin V10 aarch64 (kernel 4.19) can run Jync, but Docker or containerd must actually be installed; a supported kernel alone is not enough. Podman can run the same image.
- If Docker Hub is unreachable, use `ghcr.io/vfaner/jync:latest`. For fully offline hosts, `docker save` / `docker load` on another machine, or run `jync.jar` with a JDK 17 aarch64 build.
- On Kylin or SELinux, use named volumes or add `:Z` to bind mounts so the container can write `/app/data` and `/app/logs`.
- Default accounts are `admin / 123456` (plus `ops` and `view`); the first login forces a password change.

## One source, many targets (fan-out)

- **Supported sources**: log-based capture only (MySQL binlog, PostgreSQL logical decoding, Oracle / Dameng LogMiner, SQL Server CDC, YashanDB YStream). Polling and full-compare have no fan-out runner; a Redis cache target cannot be the primary target.
- **How to configure**: on the project form, pick several targets in the single target box — the first chip is the primary, the rest are extras. Changing the list requires the project to be stopped; adding a target clears the shared anchor so the next start re-runs the baseline for every target.
- **How many**: 20 by default (1 primary + 19 extras). The system parameter `sync.fanout.max-targets` (2–100) raises it; an administrator can also have the AI assistant change it. The change applies to later saves; running projects are not stopped.
- **Baseline**: the source is read once and rows are fanned out; keyless tables are skipped; each target creates its own tables. The baseline waits up to 5 minutes for each target to acknowledge the snapshot; after the timeout capture starts anyway, only acknowledged targets get the "baseline done" progress mark, and the lagging target's spool retains the snapshot and later events until it catches up. A 2 GB spool or a corrupt record stops that target and requires a re-baseline on the next start.
- **DDL**: not applied automatically by default (same as single-target; it opens a change-log task). A single-target project may enable "Auto-add nullable CDC columns"; fan-out still requires manual execution. When an administrator runs the statement from the change log, it is rewritten for each target (schema, quoting, table mapping) and executed on **every** target; a failing target is named on the task and the task stays retryable. "Replay missing rows" also replays into every target. Cross-engine syntax differences must be adjusted by hand.
- **Auto-sync new tables**: with the switch on, tables created while running join per engine — MySQL binlog at start/reconnect; PG, LogMiner, SQL Server and YStream scan every minute. Oracle tables get ALL COLUMN LOGGING automatically (needs ALTER privilege); Dameng is covered by its system parameter; SQL Server creates a capture instance reusing the role of an existing one; YStream adds the table to the running service after enabling supplemental logging. An extra target creates and reloads the table on its first change.
- **Target self-heal**: a missing **table** on an extra target is recreated from the source structure, reloaded and the buffered transaction replayed; a missing **column** is not repaired automatically — that worker stops, the backlog grows, and a replay happens after the column is aligned manually. Hitting the spool cap or an undecodable record is fatal for that target for the run and requires a re-baseline on the next start.
- **Event transport**: task detail → sync options → event transport:
  - Local queue (default): `data/fanout/<project id>/<target id>.log`, 2 GB cap per target, no external dependency; a full spool or an undecodable record stops that target for the run, writes a `.failed` marker, and requires a re-baseline on the next start;
  - Redis Stream: pick a configured **single-node** Redis target (the same connection and encrypted password the cache projection uses). One stream `jync:fanout:<project id>:events` per project, one consumer group per target, transaction-level ACK, pending entries re-read after a worker restart; a failed XACK stops the worker and replays from the PEL instead of pretending success. A group created for a run that will publish a baseline starts at the stream tail (the baseline republishes what the target needs); a normal resume still creates groups at the head and replays the backlog. Cluster targets are not supported; the stream is not trimmed by default — monitor with `XINFO STREAM`. `sync.fanout.redis-max-len` (0 = off) enables an approximate MAXLEN; trimming only runs when every active target has acknowledged the last published event, so unread events are never trimmed away.
  - Deleting a project, switching back to the local queue, or changing the Redis target deletes the whole stream; removing some targets runs XGROUP DESTROY for their groups. Cleanup is best-effort. Trimming is group-ack aware, so it cannot drop unread events; still size the cap for the slowest target and monitor XINFO.
  - Multi-instance / cross-node deployments must use Redis (or a shared data directory); otherwise undelivered events in a node-local spool are invisible after a failover.
- **Performance**: one source log stream, one baseline read, one catalog scan a minute and one metadata read per table — independent of target count; target writes run in parallel. Costs sit on Jync and the targets: one worker, one pool seat and one queue/consumer group per target. For 20 targets check each target's `max_connections` and the spool disk cap (2 GB per target). Change-log and per-table change counts are claimed once per source transaction, so they do not multiply by target count.
- **Positions**: the detail page shows each target's file:offset / LSN / SCN, applied transactions, the worker's last error and spool backlog. Positions are display/diagnostic state, written at most once a second per target; recovery uses the transport's acknowledged offset. A backlog that only grows with a worker error means that target cannot accept writes — read the table/column named in the error.
- **Troubleshooting**: one target behind — backlog and worker error; all targets behind — source log/network and capture error; no data after adding a target — the project must have been stopped so the baseline re-ran; Redis transport won't start — the selected Redis target must be reachable and not a cluster.
