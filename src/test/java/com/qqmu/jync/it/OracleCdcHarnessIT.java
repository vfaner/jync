package com.qqmu.jync.it;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real-Oracle harness for the LogMiner CDC integration tests (v1.6.0).
 *
 * <p>Mirrors {@link CdcMySqlContainerHarnessIT}'s family conventions: ONE container shared
 * by default, started lazily from the first {@code @BeforeEach} and deliberately NOT managed
 * by {@code @Container} (the Testcontainers extension would stop it after every class); Ryuk
 * reclaims it when the JVM exits. Classes whose tests mutate database state destructively
 * (archive deletion, container freeze) MUST override {@link #createContainer()} to run
 * against their OWN instance instead of poisoning the shared one.
 *
 * <p>Two schema users on that one instance — {@code SRC} (mining source) and {@code TGT}
 * (replication target), one PDB, mirroring the MySQL family's src/tgt dual schema. Unlike
 * MySQL's single-root model, Oracle privileges are per user, so each side gets its own
 * account; passwords travel through {@code CryptoUtil.encrypt} exactly like a UI-saved
 * connection once configs are built on top.
 *
 * <p><b>Image choice (ledgered ruling, Task 1):</b> {@code gvenzl/oracle-free:23} (23ai
 * Free), NOT the planned {@code gvenzl/oracle-free:21.3.0-xe} — that tag does not exist in
 * the oracle-free repository, and 21c XE lives under {@code gvenzl/oracle-xe}, which has no
 * arm64 build; the amd64 image fails with ORA-00443 (PMON) under Apple-Silicon emulation,
 * as already documented on {@code OracleMigrationHarnessIT} (v1.4.0), whose green runs this
 * harness's image choice follows. LogMiner behavior asserted by this family (mining inside
 * the connected PDB, ALL COLUMNS supplemental logging, SQL_REDO shape) is 12c+ family
 * behavior; the 11g-specific code branches are unit-test-only by scope decision.
 *
 * <p><b>Provisioning (measured, fixed sequence):</b> the stock image boots in NOARCHIVELOG
 * with no supplemental logging — LogMiner is impossible there — so the first use of each
 * container instance runs, via in-container {@code sqlplus / as sysdba}:
 * <pre>
 *   SHUTDOWN IMMEDIATE; STARTUP MOUNT; ALTER DATABASE ARCHIVELOG;
 *   ALTER DATABASE OPEN; ALTER PLUGGABLE DATABASE ALL OPEN;
 *   ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;   -- database-level minimal (PK+ROWID)
 *   ALTER SYSTEM SWITCH LOGFILE;
 * </pre>
 * then re-waits for JDBC and hard-verifies {@code V$DATABASE.LOG_MODE='ARCHIVELOG'} and the
 * PDB {@code READ WRITE} before any test runs. Note the two order fixes against the task
 * brief's draft sequence, both forced by measurement: {@code ADD SUPPLEMENTAL LOG DATA}
 * needs an OPEN database (ORA-01109 in MOUNT state), and {@code ALTER PLUGGABLE DATABASE
 * ALL OPEN} is required because the PDB does not reopen by itself after the instance
 * restart (no saved open state in this image).
 *
 * <p><b>Measured privilege minimum for the mining user (SRC), PDB-local:</b>
 * {@code CREATE SESSION, CREATE TABLE, UNLIMITED TABLESPACE, SELECT ANY TRANSACTION} +
 * {@code EXECUTE ON SYS.DBMS_LOGMNR} + {@code SELECT ON SYS.V_$DATABASE / V_$LOG /
 * V_$LOGFILE / V_$ARCHIVED_LOG / V_$LOGMNR_CONTENTS}. The 12c+ {@code LOGMINING} role is
 * granted too, but measurement showed it does NOT by itself expose V_$DATABASE to a
 * PDB-local user (ORA-00942) — the explicit per-view grants (the standard CDB/PDB recipe)
 * are what actually carry the mining cycle; the v1.6.0 README prerequisites must list
 * them for PDB deployments. The 11g path instead needs
 * EXECUTE_CATALOG_ROLE/SELECT_CATALOG_ROLE (probed via SESSION_ROLES in production code,
 * not by this container family). Users MUST be created inside the PDB, not in CDB$ROOT
 * where the sysdba bequeath connection lands — see {@link #ensureUsers} for the measured
 * ORA-01045 story.
 *
 * <p><b>Dual-mode mining (measured ORA-65040, user-approved path A):</b> on a CDB,
 * {@code DBMS_LOGMNR.ADD_LOGFILE/START_LOGMNR} may only execute in CDB$ROOT — a PDB-local
 * mining session is rejected outright. The harness therefore also provisions
 * {@link #MINER_USER}: a C## common user in root whose session mines redo for the whole
 * CDB, with {@code V$LOGMNR_CONTENTS.SRC_CON_ID} filtered down to the one PDB under test
 * (the id itself read from the PDB side via {@link #Q_DATABASE_CDB_INFO}). Non-CDB
 * deployments need no miner user — the project's own source connection mines directly;
 * that branch is unit-test-only, since every containerized Oracle since 18c XE is a CDB.
 *
 * <p>The three log-list query constants below are the FINAL, measured forms — the v1.6.0
 * LogMiner session code references them instead of re-deriving its own SQL.
 *
 * <p>Spring side: the full application context on in-memory H2 metadata ({@code oracdc},
 * never the file store), MOCK web environment (no port binding), fast lease/supervise/
 * reconcile intervals like the MySQL family.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "sync.cdc.lock-ttl-ms=3000",
        "sync.cdc.supervise-interval-ms=1000",
        "sync.lock-ttl-ms=3000",
        "sync.reconcile-interval-ms=1000",
        "sync.shutdown-wait-ms=5000",
        "spring.datasource.url=jdbc:h2:mem:oracdc;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.generate-unique-name=false",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false"})
public abstract class OracleCdcHarnessIT {

    protected static final String SRC_SCHEMA = "SRC";
    protected static final String TGT_SCHEMA = "TGT";
    protected static final String SRC_PASSWORD = "Src#It2026";
    protected static final String TGT_PASSWORD = "Tgt#It2026";

    /**
     * Root-level mining user for the CDB mode (measured ORA-65040: on a CDB,
     * DBMS_LOGMNR.ADD_LOGFILE/START_LOGMNR may only run in CDB$ROOT, as a common user —
     * hence the mandatory C## prefix). Mirrors the product's optional「挖掘账号」
     * ({@code DatabaseConfig.minerUsername/minerPassword}) for CDB deployments.
     */
    protected static final String MINER_USER = "C##MINER";
    protected static final String MINER_PASSWORD = "Min#It2026";

    /** Current SCN of the connected database — the baseline anchor and resume watermark. */
    public static final String Q_CURRENT_SCN = "SELECT CURRENT_SCN FROM V$DATABASE";

    /**
     * CDB detection + this session's container id, in one query (pinned for the probe and
     * the session factory): from a PDB session {@code CDB='YES'} means dual-mode routing
     * and {@code CON_ID} is exactly the id the root-mined V$LOGMNR_CONTENTS
     * <b>{@code SRC_CON_ID}</b> column must be filtered by (measured on 23ai: the view's
     * own {@code CON_ID} column is always 1 when queried from CDB$ROOT — it is the querying
     * container's meta column, NOT the row's origin; {@code SRC_CON_ID} carries the PDB that
     * actually generated each redo record, populated under DICT_FROM_ONLINE_CATALOG).
     * Measured trap: {@code V$DATABASE.CON_ID} itself reads 0 even from a
     * PDB session (it is a CDB-level view) — the container id MUST come from
     * {@code SYS_CONTEXT('USERENV','CON_ID')}. 11g has neither the CDB column nor that
     * context attribute: the production probe treats 11g as non-CDB by version branch and
     * never runs this form there.
     */
    public static final String Q_DATABASE_CDB_INFO =
            "SELECT NAME, CDB, SYS_CONTEXT('USERENV','CON_ID') AS CON_ID FROM V$DATABASE";

    /** Member file paths of the CURRENT online redo group (server-side absolute paths). */
    public static final String Q_CURRENT_REDO_MEMBERS =
            "SELECT MEMBER FROM V$LOGFILE WHERE GROUP# IN (SELECT GROUP# FROM V$LOG WHERE STATUS = 'CURRENT')";

    /**
     * Local-destination archived logs whose coverage extends past the given SCN (bind 1),
     * oldest first. {@code NAME IS NOT NULL} drops rows for already-deleted archives;
     * {@code DEST_ID = 1} is the primary local destination.
     */
    public static final String Q_ARCHIVED_LOGS_SINCE =
            "SELECT NAME FROM V$ARCHIVED_LOG WHERE DEST_ID = 1 AND NEXT_CHANGE# > ? AND NAME IS NOT NULL"
                    + " ORDER BY FIRST_CHANGE#";

    /** The family-shared Oracle instance; see {@link #createContainer()}. */
    private static OracleContainer sharedOracle;

    /** Containers already ARCHIVELOG-provisioned in THIS JVM (per-instance, identity-keyed). */
    private static final Set<OracleContainer> PROVISIONED =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Builds a fresh 23ai Free instance with a generous startup timeout (first boot
     * initializes the database from the image seed).
     */
    protected static OracleContainer configuredOracle() {
        return new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                .withStartupTimeoutSeconds(600);
    }

    /**
     * The container this test class runs against. Default: the single instance shared by
     * the whole family. Destructive-state classes override this to return their OWN
     * instance (same contract as the MySQL family).
     */
    protected OracleContainer createContainer() {
        synchronized (OracleCdcHarnessIT.class) {
            if (sharedOracle == null) {
                sharedOracle = configuredOracle();
            }
            return sharedOracle;
        }
    }

    /**
     * Instance method, not {@code @BeforeAll}: {@link #createContainer()} is overridable
     * per subclass, and an override cannot be called from a static context. Provisioning
     * runs exactly once per container instance per JVM ({@link #PROVISIONED}).
     */
    @BeforeEach
    void startOracle() {
        OracleContainer oracle = createContainer();
        synchronized (OracleCdcHarnessIT.class) {
            if (PROVISIONED.contains(oracle)) {
                return;
            }
            if (!oracle.isRunning()) {
                oracle.start();
            }
            provision(oracle);
            PROVISIONED.add(oracle);
        }
    }

    // --- provisioning ------------------------------------------------------------------------

    private void provision(OracleContainer oracle) {
        enableArchivelog(oracle);
        waitFor("Oracle JDBC available again after the ARCHIVELOG instance restart",
                300_000, this::canConnect);
        ensureUsers(oracle);
        verifyArchivelog(oracle);
    }

    /**
     * The measured sequence from the class javadoc. NO {@code WHENEVER SQLERROR} here on
     * purpose: {@code ALTER PLUGGABLE DATABASE ALL OPEN} raises a benign ORA-65019 when the
     * PDB reopened on its own, and the hard gate is {@link #verifyArchivelog} anyway — a
     * failed conversion shows up there with the full sqlplus output attached.
     */
    private void enableArchivelog(OracleContainer oracle) {
        execSqlplusRaw(oracle, "/ as sysdba", String.join("\n",
                "SHUTDOWN IMMEDIATE;",
                "STARTUP MOUNT;",
                "ALTER DATABASE ARCHIVELOG;",
                "ALTER DATABASE OPEN;",
                "ALTER PLUGGABLE DATABASE ALL OPEN;",
                "ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;",
                "ALTER SYSTEM SWITCH LOGFILE;",
                "EXIT;"));
    }

    /**
     * Creates SRC/TGT as LOCAL users of the PDB. Measured pitfall (first spike run,
     * ORA-01045): the sysdba bequeath connection lands in CDB$ROOT, and 23ai happily
     * creates non-C## users there as COMMON users — visible in every PDB but carrying
     * their grants only in root, so a PDB login is denied for lack of local CREATE
     * SESSION. Hence the explicit container switch. The tolerated-error pre-drop removes
     * leftovers in BOTH containers (a common user in root would block PDB-local creation
     * with ORA-01920) so re-provisioning stays idempotent.
     */
    private void ensureUsers(OracleContainer oracle) {
        String pdb = oracle.getDatabaseName().toUpperCase(Locale.ROOT);
        execSqlplusRaw(oracle, "/ as sysdba", String.join("\n",
                "DROP USER " + MINER_USER + " CASCADE;",
                "DROP USER SRC CASCADE;",
                "DROP USER TGT CASCADE;",
                "ALTER SESSION SET CONTAINER=" + pdb + ";",
                "DROP USER SRC CASCADE;",
                "DROP USER TGT CASCADE;",
                "EXIT;"));
        // C##MINER: common user in CDB$ROOT (the bequeath session's default container) —
        // the only place a LogMiner session may run on a CDB (ORA-65040, measured).
        runSqlplus(oracle, "/ as sysdba", String.join("\n",
                "WHENEVER SQLERROR EXIT SQL.SQLCODE",
                "CREATE USER " + MINER_USER + " IDENTIFIED BY \"" + MINER_PASSWORD + "\";",
                "GRANT CREATE SESSION TO " + MINER_USER + ";",
                "GRANT LOGMINING TO " + MINER_USER + ";",
                "GRANT SELECT ANY TRANSACTION TO " + MINER_USER + ";",
                "GRANT EXECUTE ON SYS.DBMS_LOGMNR TO " + MINER_USER + ";",
                "GRANT SELECT ON SYS.V_$DATABASE TO " + MINER_USER + ";",
                "GRANT SELECT ON SYS.V_$LOG TO " + MINER_USER + ";",
                "GRANT SELECT ON SYS.V_$LOGFILE TO " + MINER_USER + ";",
                "GRANT SELECT ON SYS.V_$ARCHIVED_LOG TO " + MINER_USER + ";",
                "GRANT SELECT ON SYS.V_$LOGMNR_CONTENTS TO " + MINER_USER + ";",
                "EXIT;"));
        runSqlplus(oracle, "/ as sysdba", String.join("\n",
                "WHENEVER SQLERROR EXIT SQL.SQLCODE",
                "ALTER SESSION SET CONTAINER=" + pdb + ";",
                "CREATE USER SRC IDENTIFIED BY \"" + SRC_PASSWORD + "\";",
                "GRANT CREATE SESSION TO SRC;",
                "GRANT CREATE TABLE TO SRC;",
                "GRANT UNLIMITED TABLESPACE TO SRC;",
                "GRANT LOGMINING TO SRC;",
                "GRANT SELECT ANY TRANSACTION TO SRC;",
                // Measured on 23ai Free: the LOGMINING role alone did NOT expose V_$DATABASE
                // to a PDB-local user (ORA-00942), so the explicit PDB-standard grants follow.
                "GRANT EXECUTE ON SYS.DBMS_LOGMNR TO SRC;",
                "GRANT SELECT ON SYS.V_$DATABASE TO SRC;",
                "GRANT SELECT ON SYS.V_$LOG TO SRC;",
                "GRANT SELECT ON SYS.V_$LOGFILE TO SRC;",
                "GRANT SELECT ON SYS.V_$ARCHIVED_LOG TO SRC;",
                "GRANT SELECT ON SYS.V_$LOGMNR_CONTENTS TO SRC;",
                "CREATE USER TGT IDENTIFIED BY \"" + TGT_PASSWORD + "\";",
                "GRANT CREATE SESSION TO TGT;",
                "GRANT CREATE TABLE TO TGT;",
                "GRANT UNLIMITED TABLESPACE TO TGT;",
                "EXIT;"));
    }

    private void verifyArchivelog(OracleContainer oracle) {
        Container.ExecResult result = execSqlplusRaw(oracle, "/ as sysdba", String.join("\n",
                "SET PAGESIZE 0 HEADING OFF FEEDBACK OFF LINESIZE 200",
                "SELECT 'MODE=' || LOG_MODE FROM V$DATABASE;",
                "SELECT 'PDB=' || NAME || ':' || OPEN_MODE FROM V$PDBS;",
                "EXIT;"));
        String out = result.getStdout() + result.getStderr();
        if (!out.contains("MODE=ARCHIVELOG") || !out.contains(":READ WRITE")) {
            throw new IllegalStateException(
                    "Oracle provisioning verification failed (exit " + result.getExitCode() + "):\n" + out);
        }
    }

    // --- sqlplus plumbing --------------------------------------------------------------------

    /**
     * Runs a sqlplus script inside the container as the given harness user
     * ({@code SRC}/{@code TGT}), hard-failing on any ORA-/SP2-/PLS- output or nonzero exit.
     */
    protected void execSql(String user, String script) {
        OracleContainer oracle = createContainer();
        String connect = user + "/" + passwordFor(user)
                + "@//localhost:1521/" + oracle.getDatabaseName();
        runSqlplus(oracle, connect, script);
    }

    /** {@code sysdba} sibling of {@link #execSql} for instance-level statements (SWITCH LOGFILE, …). */
    protected void execAsSysdba(String script) {
        runSqlplus(createContainer(), "/ as sysdba", script);
    }

    private void runSqlplus(OracleContainer oracle, String connect, String script) {
        Container.ExecResult result = execSqlplusRaw(oracle, connect, script);
        String out = result.getStdout() + result.getStderr();
        if (result.getExitCode() != 0
                || out.contains("ORA-") || out.contains("SP2-") || out.contains("PLS-")) {
            throw new IllegalStateException("sqlplus [" + connect + "] failed (exit "
                    + result.getExitCode() + "):\n" + out);
        }
    }

    private Container.ExecResult execSqlplusRaw(OracleContainer oracle, String connect, String script) {
        String cmd = "sqlplus -S '" + connect + "' <<'JYNC_SQL_EOF'\n" + script + "\nJYNC_SQL_EOF\n";
        try {
            return oracle.execInContainer("bash", "-c", cmd);
        } catch (IOException e) {
            throw new IllegalStateException("sqlplus exec failed: " + connect, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during sqlplus exec: " + connect, e);
        }
    }

    private static String passwordFor(String user) {
        if (SRC_SCHEMA.equalsIgnoreCase(user)) {
            return SRC_PASSWORD;
        }
        if (TGT_SCHEMA.equalsIgnoreCase(user)) {
            return TGT_PASSWORD;
        }
        throw new IllegalArgumentException("Unknown harness user: " + user);
    }

    // --- JDBC access --------------------------------------------------------------------------

    /** JDBC URL of the container's PDB (module default service, mapped port). */
    protected String jdbcUrl() {
        return createContainer().getJdbcUrl();
    }

    /** Direct SRC-schema connection (plain JDBC, no pool), autocommit left at driver default. */
    protected Connection sourceJdbc() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), SRC_SCHEMA, SRC_PASSWORD);
    }

    /** The {@code TGT}-schema twin of {@link #sourceJdbc()}. */
    protected Connection targetJdbc() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), TGT_SCHEMA, TGT_PASSWORD);
    }

    /**
     * Direct CDB$ROOT connection as the common mining user (the CDB-mode mining session).
     * The root service name is the DB_NAME read through the SRC connection —
     * {@code V$DATABASE.NAME} from a PDB session is the CDB-wide database name.
     */
    protected Connection minerJdbc() throws SQLException {
        String dbName;
        try (Connection src = sourceJdbc();
             Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("SELECT NAME FROM V$DATABASE")) {
            if (!rs.next()) {
                throw new IllegalStateException("V$DATABASE returned no row");
            }
            dbName = rs.getString(1);
        }
        String url = jdbcUrl();
        String rootUrl = url.substring(0, url.lastIndexOf('/') + 1) + dbName;
        return DriverManager.getConnection(rootUrl, MINER_USER, MINER_PASSWORD);
    }

    private boolean canConnect() {
        OracleContainer oracle = createContainer();
        try (Connection c = DriverManager.getConnection(
                oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword())) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- shared helpers (same contracts as the MySQL family) ----------------------------------

    /**
     * Polls {@code condition} every 200ms until it holds; {@link AssertionError} on
     * timeout. No Awaitility — the project does not carry that dependency. The deadline
     * is {@code nanoTime}-based: wall-clock jumps must not shorten or extend a wait.
     */
    protected void waitFor(String what, long timeoutMs, BooleanSupplier condition) {
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
            if (remainingMs <= 0) {
                throw new AssertionError(
                        "Timed out after " + timeoutMs + " ms waiting for: " + what);
            }
            try {
                Thread.sleep(Math.min(200L, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + what, e);
            }
        }
    }

    /** Runs a query; rows are label-keyed maps of raw JDBC values. */
    protected List<Map<String, Object>> queryAll(Connection conn, String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
