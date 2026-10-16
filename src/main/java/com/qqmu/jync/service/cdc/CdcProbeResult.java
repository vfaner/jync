package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import lombok.Getter;

/**
 * Itemized result of the CDC capability probe (task book B2). Never an exception — a
 * failed probe means "CDC unavailable, here is what is missing", not an error state.
 * Check names double as i18n key suffixes: cdc.check.&lt;NAME&gt;.
 */
@Getter
public final class CdcProbeResult {

    public static final String VERSION = "VERSION";
    public static final String LOG_BIN = "LOG_BIN";
    public static final String BINLOG_FORMAT = "BINLOG_FORMAT";
    public static final String BINLOG_ROW_IMAGE = "BINLOG_ROW_IMAGE";
    public static final String SERVER_ID = "SERVER_ID";
    public static final String REPLICATION_GRANTS = "REPLICATION_GRANTS";
    /**
     * P1-5: the baseline position read (SHOW BINARY LOG STATUS / SHOW MASTER STATUS)
     * actually executes — proves REPLICATION CLIENT really works (SHOW GRANTS text can
     * lie about roles/dynamic privileges) and that the server version speaks one of the
     * two statements (8.4 removed SHOW MASTER STATUS).
     */
    public static final String BINLOG_STATUS = "BINLOG_STATUS";
    public static final String BINLOG_RETENTION = "BINLOG_RETENTION";

    // —— v1.6.0 Oracle LogMiner checks ——

    /** Source must be literal Oracle (not a look-alike such as DM / OceanBase Oracle mode). */
    public static final String ORACLE_SOURCE = "ORACLE_SOURCE";
    /** LOG_MODE = ARCHIVELOG — LogMiner needs archived redo. */
    public static final String ARCHIVELOG = "ARCHIVELOG";
    /** SUPPLEMENTAL_LOG_DATA_MIN != NO — database-level minimal supplemental logging. */
    public static final String SUPPLEMENTAL_DB = "SUPPLEMENTAL_DB";
    /** Per-table ALL COLUMN LOGGING group — upsert needs the full after-image. */
    public static final String SUPPLEMENTAL_TABLES = "SUPPLEMENTAL_TABLES";
    /** Mining role present (LOGMINING on 12c+, EXECUTE_CATALOG on 11g). */
    public static final String MINING_PRIVILEGES = "MINING_PRIVILEGES";
    /** LONG / LONG RAW columns — pass-with-warning: their DML is skipped and counted. */
    public static final String LONG_COLUMNS = "LONG_COLUMNS";

    /** P0-2: emitted only on a CDB — how mining will reach CDB$ROOT (or why it can't). */
    public static final String CDB_MINING_MODE = "CDB_MINING_MODE";

    /**
     * Target side (not CDC): the configured account may actually create tables in the
     * schema the sync will write to. Reported by {@code TargetDdlProbe} on a TARGET-role
     * Oracle connection.
     */
    public static final String TARGET_DDL_PRIVILEGES = "TARGET_DDL_PRIVILEGES";

    // —— v1.7.0 PostgreSQL logical replication checks ——

    /** Source must be literal PostgreSQL (dialect-compatible look-alikes rejected). */
    public static final String PG_SOURCE = "PG_SOURCE";
    /** wal_level = logical — required for logical decoding. */
    public static final String PG_WAL_LEVEL = "PG_WAL_LEVEL";
    /** Current user has the REPLICATION attribute (or SUPERUSER). */
    public static final String PG_REPLICATION_ROLE = "PG_REPLICATION_ROLE";
    /** Free max_replication_slots headroom — an exhausted quota blocks slot creation (P1-9/I6). */
    public static final String PG_SLOT_HEADROOM = "PG_SLOT_HEADROOM";
    /** test_decoding plugin is available (shipped with vanilla PG ≥ 9.4). */
    public static final String PG_PLUGIN_AVAILABLE = "PG_PLUGIN_AVAILABLE";
    /** Per-table REPLICA IDENTITY — must be DEFAULT/PARTIAL/FULL for UPDATE/DELETE to yield PK. */
    public static final String PG_REPLICA_IDENTITY = "PG_REPLICA_IDENTITY";

    // —— v1.7.0 SQL Server CDC checks ——

    /** Source must be literal Microsoft SQL Server. */
    public static final String MSSQL_SOURCE = "MSSQL_SOURCE";
    /** Database has CDC enabled (is_cdc_enabled = 1). */
    public static final String MSSQL_CDC_DB_ENABLED = "MSSQL_CDC_DB_ENABLED";
    /** SQL Server Agent is running (required for capture/cleanup jobs). */
    public static final String MSSQL_AGENT_RUNNING = "MSSQL_AGENT_RUNNING";
    /** Current user is in cdc_admin / cdc_reader (or sysadmin). */
    public static final String MSSQL_CDC_ROLE = "MSSQL_CDC_ROLE";
    /** Per-table CDC capture instance exists in cdc.change_tables. */
    public static final String MSSQL_CAPTURE_INSTANCE = "MSSQL_CAPTURE_INSTANCE";

    public static final class Check {
        private final String name;
        private final boolean passed;
        private final String detail;

        private Check(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }

        public static Check of(String name, boolean passed, String detail) {
            return new Check(name, passed, detail);
        }

        public String getName() { return name; }
        public boolean isPassed() { return passed; }
        public String getDetail() { return detail; }
    }

    private final List<Check> checks;

    private CdcProbeResult(List<Check> checks) {
        this.checks = Collections.unmodifiableList(new ArrayList<>(checks));
    }

    public static CdcProbeResult of(List<Check> checks) {
        return new CdcProbeResult(checks);
    }

    public boolean passed() {
        return checks.stream().allMatch(Check::isPassed);
    }

    public List<Check> failures() {
        return checks.stream().filter(c -> !c.isPassed()).collect(Collectors.toList());
    }
}
