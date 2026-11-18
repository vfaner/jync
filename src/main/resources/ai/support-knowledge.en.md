# Jync knowledge

## Author and contact

Jync is developed and maintained by 沐沐开源工坊 under the MIT license. Official website:
https://jync.qqmu.com. Contact: QQ 817094 / 2912167928; WeChat qqmu66; QQ group 426669837;
email vfaner@88.com. The Jync name, logo and author attribution must not be replaced when
redistributing — see TRADEMARK.md in the repository.

## Creating a sync task

Open Sync tasks → Real-time sync → New task. Pick a SOURCE connection and a TARGET connection,
save, then choose tables and a strategy on the detail page. Use a Redis cache target by choosing
the REDIS target kind.

## The three capture strategies

- Full load: copies every row once; use it for the initial load.
- Timestamp polling: pulls rows after a cursor column; the source needs a reliable time or
  identity column.
- Log CDC: replays database logs (MySQL binlog, Oracle LogMiner, PostgreSQL logical decoding,
  SQL Server CDC). Lowest latency, but the source must enable logging and grant access.

## Oracle CDC setup

1. Create the source connection with role SOURCE and type Oracle.
2. Fill in the mining user and password; it needs LogMiner privileges.
3. On the task detail page switch the strategy to log CDC, select tables and start.
4. In a CDB, make sure the mining user can read the target PDB's archive logs.

## ETL tasks and variables

ETL runs the customer's own SELECT and copies the rows. Built-in variables include `${date}`,
`${datetime}`, `${yesterday}`, `${month}`, `${year}`, `${taskName}` and `${targetTable}`; custom
`name=value` variables can be added per task. Unknown variables are rejected on save, precheck and
run.

## Schema sync

Schema sync compares tables, indexes, views and routines, then shows the generated DDL for review
before applying. Cross-database runs map types, functions and identifier quoting by dialect family.

## Connections and drivers

Drivers are bundled except GBase, ShenTong, GaussDB, SAP HANA, ClickHouse, Hive, Impala,
Elasticsearch and MongoDB. For those, provide the vendor jar path in the connection form; override
the driver class on the same card when it differs.

## Alerts

Alerts cover sync failures, stalled polling, idle projects, stopped CDC, drift, connection health
and PG slot backlog. Generic JSON, DingTalk, WeCom and Feishu are supported, with cooldown and
recovery notices. Webhook URLs are stored encrypted.

## Troubleshooting

- Running but no progress: check the activity cycle and minutes since progress on the detail page.
- Row counts differ: compare COUNT(*) on both sides, then check cursor monotonicity and open
  transactions.
- CDC lag grows: check log retention, consumer position and network; an expired position needs a
  new baseline.
- Connection test fails: check port, grants and firewall; for vendor drivers check the jar path
  and driver class.
