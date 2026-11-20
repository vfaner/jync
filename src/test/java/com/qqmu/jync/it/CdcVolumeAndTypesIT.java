package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * 验收 B①(十万条一致性)+ 验收 B④(全类型矩阵往返)。
 *
 * <p>Runs against the shared real MySQL 8 binlog source of
 * {@link CdcMySqlContainerHarnessIT}; named {@code *IT} so surefire's default includes
 * skip it — run explicitly with
 * {@code mvn test -Dtest='CdcVolumeAndTypesIT,CdcResilienceIT' -Dapi.version=1.44}.
 *
 * <p>Type matrix design: one {@code @Test} per type (the brief's recommended "笨但更好
 * 调试" shape over {@code @ParameterizedTest}), each with its OWN single-column table and
 * OWN project. Sharing one project across all types would let one poisonous value (the
 * TIME extremes, which Connector/J refuses to materialize) fail the shared baseline and
 * take every other type down with it. Every test ends in {@code finally} with
 * {@link #finish(Project)}: ensureStopped → disableProject (so the 1s reconcile/supervise
 * ticks never relaunch it) → evict both Hikari pools (~26 projects × 2 pools would
 * otherwise creep toward MySQL's max_connections=151).
 *
 * <p>Temporal assertions run through server-side {@code DATE_FORMAT}/{@code TIME_FORMAT}
 * strings on the TARGET connection: exact wall-clock values, immune to driver-side
 * materialization (reading {@code TIME '838:59:59'} via {@code getObject} throws in
 * Connector/J — the value must never leave the server as a Java object) and to JVM
 * timezone. Values are inserted/updated as SQL literals so the driver never encodes them
 * on the way IN; only the CDC pipeline's own writes go through Java.
 *
 * <p>Table names are class-disjoint from Task 14's ({@code orders}, {@code crash_t}
 * survive in the shared container across classes): {@code vol_orders} + {@code type_*}.
 */
class CdcVolumeAndTypesIT extends CdcMySqlContainerHarnessIT {

    private static final Logger log = LoggerFactory.getLogger(CdcVolumeAndTypesIT.class);

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;
    @Autowired private DataSourceManager dataSourceManager;

    // ================= 验收 B① — 十万条 + ~10k 混合 DML(含改主键) =================

    @Test
    void hundredThousandMixedDmlReachesConsistency() throws Exception {
        String table = "vol_orders";
        long seedStart = System.nanoTime();
        String ddl = "CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, name VARCHAR(64))";
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute(ddl);
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute(ddl);
        }
        // 100_000 rows via JDBC batch, one commit per 1000 (brief spec). The test-local
        // URL adds rewriteBatchedStatements only for SEEDING speed — the production
        // pipeline under test keeps the DatabaseType URL template untouched.
        try (Connection conn = DriverManager.getConnection(
                jdbcUrl(SRC_SCHEMA) + "&rewriteBatchedStatements=true",
                "root", createContainer().getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + table + " (id, name) VALUES (?, ?)")) {
            conn.setAutoCommit(false);
            for (int i = 1; i <= 100_000; i++) {
                ps.setLong(1, i);
                ps.setString(2, "vol-" + i);
                ps.addBatch();
                if (i % 1_000 == 0) {
                    ps.executeBatch();
                    conn.commit();
                }
            }
            ps.executeBatch();
            conn.commit();
        }
        log.info("MEASURED volume seed (100k rows, batch 1000): {} ms", ms(seedStart));

        Project project = null;
        try {
            project = newProject("cdc-volume", cdcConfig(table));
            final Long projectId = project.getId();   // lambda capture (project is reassigned)
            long baselineStart = System.nanoTime();
            manager.ensureStarted(project);
            waitFor("volume baseline done (100k rows)", 300_000, () -> baselineDone(projectId));
            waitFor("100k rows on target", 120_000, () -> countTarget(table) == 100_000L);
            log.info("MEASURED volume baseline (100k-row full load): {} ms", ms(baselineStart));

            // ~10k mixed DML: 100 transactions × (30 INSERT + 40 UPDATE + 20 PK-move +
            // 10 DELETE). Per batch b: inserts land at 100_001+30b.., updates hit ids
            // 40b+1..40b+40 (all of 1..4000 over the run), PK moves send 5001+20b.. to
            // +200_000, deletes remove 8001+10b... Net +20 rows per batch, so the target
            // count climbs monotonically to 102_000 and reaches it ONLY when the final
            // transaction has been applied — the count is a sound convergence gate.
            long dmlStart = System.nanoTime();
            try (Connection src = sourceJdbc()) {
                src.setAutoCommit(false);
                try (Statement st = src.createStatement()) {
                    for (int b = 0; b < 100; b++) {
                        for (int j = 0; j < 30; j++) {
                            long id = 100_001L + 30L * b + j;
                            st.execute("INSERT INTO " + table + " VALUES (" + id + ", 'vol-" + id + "')");
                        }
                        for (int j = 1; j <= 40; j++) {
                            long id = 40L * b + j;
                            st.execute("UPDATE " + table + " SET name = 'upd-" + id + "' WHERE id = " + id);
                        }
                        for (int j = 0; j < 20; j++) {
                            long id = 5_001L + 20L * b + j;
                            st.execute("UPDATE " + table + " SET id = id + 200000 WHERE id = " + id);
                        }
                        for (int j = 0; j < 10; j++) {
                            long id = 8_001L + 10L * b + j;
                            st.execute("DELETE FROM " + table + " WHERE id = " + id);
                        }
                        src.commit();
                    }
                }
            }
            log.info("MEASURED volume DML issued (10k changes, 100 txns): {} ms", ms(dmlStart));

            waitFor("volume converged after 10k mixed DML", 180_000,
                    () -> countTarget(table) == 102_000L);
            log.info("MEASURED volume convergence (DML start -> target consistent): {} ms", ms(dmlStart));

            // Whole-table checksum equality — the brief's MD5-style check.
            String checksum = "SELECT COUNT(*) AS c, SUM(CRC32(CONCAT_WS('|', id, name))) AS s FROM " + table;
            Map<String, Object> srcSum;
            try (Connection src = sourceJdbc()) {
                srcSum = queryAll(src, checksum).get(0);
            }
            Map<String, Object> tgtSum = queryTarget(checksum).get(0);
            assertThat(tgtSum.get("c")).isEqualTo(102_000L);
            assertThat(String.valueOf(tgtSum.get("s")))
                    .as("SUM(CRC32(CONCAT_WS('|', id, name))) equal on both sides")
                    .isEqualTo(String.valueOf(srcSum.get("s")));

            // 逐行抽样比对: raw src-vs-tgt equality over a sample spanning every DML class,
            // plus exact expectations pinning each class's semantics.
            List<Long> sampleIds = List.of(1L, 2L, 1_234L, 4_000L, 4_001L, 5_000L, 8_000L,
                    9_001L, 90_000L, 100_000L, 100_001L, 101_500L, 103_000L,
                    205_001L, 205_501L, 207_000L);
            StringBuilder in = new StringBuilder();
            for (Long id : sampleIds) {
                if (in.length() > 0) {
                    in.append(", ");
                }
                in.append(id);
            }
            try (Connection src = sourceJdbc()) {
                List<Map<String, Object>> srcRows = queryAll(src,
                        "SELECT id, name FROM " + table + " WHERE id IN (" + in + ") ORDER BY id");
                assertThat(queryTarget("SELECT id, name FROM " + table
                        + " WHERE id IN (" + in + ") ORDER BY id"))
                        .as("sampled rows identical to source")
                        .isEqualTo(srcRows);
            }
            assertThat(queryTarget("SELECT name FROM " + table + " WHERE id = 1234").get(0).get("name"))
                    .isEqualTo("upd-1234");                               // UPDATE 过的行 name 相等
            assertThat(queryTarget("SELECT name FROM " + table + " WHERE id = 4001").get(0).get("name"))
                    .isEqualTo("vol-4001");                               // just outside the update ranges
            assertThat(queryTarget("SELECT id FROM " + table + " WHERE id = 5501")).isEmpty();      // PK moved away
            assertThat(queryTarget("SELECT name FROM " + table + " WHERE id = 205501").get(0).get("name"))
                    .isEqualTo("vol-5501");                               // PK landed
            assertThat(queryTarget("SELECT id FROM " + table + " WHERE id = 8500")).isEmpty();      // deleted
            assertThat(queryTarget("SELECT id FROM " + table + " WHERE id = 101500")).hasSize(1);  // inserted
        } finally {
            if (project != null) {
                finish(project);
            }
        }
    }

    // ================= 验收 B④ — 类型矩阵,每类型一个 @Test =================

    @Test void tinyintRoundTrip() {
        roundTrip("tinyint", "TINYINT", "-128", "v", -128, "127", 127);
    }

    /**
     * {@code TINYINT(1)} is reported as JDBC BIT by Connector/J and was collapsed to 0/1
     * on both legs: the full load's {@code getObject} handed back Boolean, and the stream
     * bind turned any number into a flag. A status stored as 5 reached the target as 1 and
     * no rebaseline could repair it (2.4.3 现场反馈). Both columns here are TINYINT(1), so
     * the target metadata reports BIT and the buggy bind path is the one exercised.
     */
    @Test void tinyint1BooleanWidthRoundTrip() {
        roundTrip("tinyint1", "TINYINT(1)", "5", "v + 0", 5, "0", 0);
    }

    @Test void smallintRoundTrip() {
        roundTrip("smallint", "SMALLINT", "-32768", "v", -32768, "32767", 32767);
    }

    @Test void mediumintRoundTrip() {
        roundTrip("mediumint", "MEDIUMINT", "-8388608", "v", -8388608, "8388607", 8388607);
    }

    @Test void intRoundTrip() {
        roundTrip("int", "INT", "-2147483648", "v", -2147483648, "2147483647", 2147483647);
    }

    @Test void bigintRoundTrip() {
        roundTrip("bigint", "BIGINT", "-9223372036854775808", "v", Long.MIN_VALUE,
                "9223372036854775807", Long.MAX_VALUE);
    }

    @Test void floatRoundTrip() {
        roundTrip("float", "FLOAT", "1.5", "v", 1.5f, "-0.000001", -0.000001f);
    }

    @Test void doubleRoundTrip() {
        roundTrip("double", "DOUBLE", "1.5", "v", 1.5d, "-0.000001", -0.000001d);
    }

    @Test void decimalRoundTrip() {
        roundTrip("decimal", "DECIMAL(20,6)", "12345678901234.567890", "v",
                new BigDecimal("12345678901234.567890"),
                "-12345678901234.567890", new BigDecimal("-12345678901234.567890"));
    }

    @Test void charRoundTrip() {
        // 定长补空格语义:比对 trim 后值(流式路径的 CHAR binlog 是补满宽度的定长字节)。
        roundTrip("char10", "CHAR(10)", "'hello'", "TRIM(v)", "hello", "'world'", "world");
    }

    @Test void varcharRoundTrip() {
        roundTrip("varchar", "VARCHAR(255)", "'varchar 值'", "v", "varchar 值",
                "'varchar 更新值 second'", "varchar 更新值 second");
    }

    @Test void textRoundTrip() {
        roundTrip("text", "TEXT", "'中文 text 😀 first'", "v", "中文 text 😀 first",
                "'中文 text 🌟 second'", "中文 text 🌟 second");
    }

    @Test void dateRoundTrip() {
        roundTrip("date", "DATE", "'2026-10-10'", "DATE_FORMAT(v, '%Y-%m-%d')",
                "2026-10-10", "'2027-12-31'", "2027-12-31");
    }

    @Test void datetimeRoundTrip() {
        roundTrip("datetime", "DATETIME(6)", "'2026-10-10 12:34:56.789012'",
                "DATE_FORMAT(v, '%Y-%m-%d %H:%i:%s.%f')", "2026-10-10 12:34:56.789012",
                "'2026-10-11 06:07:08.000009'", "2026-10-11 06:07:08.000009");
    }

    @Test void timestampRoundTrip() {
        // TIMESTAMP NULL: keeps MySQL from bolting the implicit
        // DEFAULT/ON UPDATE CURRENT_TIMESTAMP onto the first TIMESTAMP column; the
        // container runs default-time-zone=+00:00 and both JDBC URLs pin
        // serverTimezone=UTC, so the expected wall clock is the literal we inserted.
        roundTrip("timestamp", "TIMESTAMP NULL", "'2026-10-10 01:02:03'",
                "DATE_FORMAT(v, '%Y-%m-%d %H:%i:%s.%f')", "2026-10-10 01:02:03.000000",
                "'2026-10-10 23:59:59'", "2026-10-10 23:59:59.000000");
    }

    @Test void bit1RoundTrip() {
        roundTrip("bit1", "BIT(1)", "b'1'", "v + 0", 1L, "b'0'", 0L);
    }

    @Test void bit16RoundTrip() {
        roundTrip("bit16", "BIT(16)", "b'1010101010101010'", "v + 0", 43690L,
                "b'1111111111111111'", 65535L);
    }

    @Test void blobRoundTrip() {
        roundTrip("blob", "BLOB", "X'00FF'", "HEX(v)", "00FF", "X'DEADBEEF'", "DEADBEEF");
    }

    @Test void tinyblobRoundTrip() {
        roundTrip("tinyblob", "TINYBLOB", "X'0102'", "HEX(v)", "0102", "X'FF'", "FF");
    }

    @Test void longblobRoundTrip() {
        roundTrip("longblob", "LONGBLOB", "CONCAT(X'00FF', REPEAT(X'41', 4096))", "HEX(v)",
                "00FF" + "41".repeat(4096), "X'DEAD'", "DEAD");
    }

    @Test void latin1RoundTrip() {
        roundTrip("latin1", "VARCHAR(100) CHARACTER SET latin1", "'café'", "v", "café",
                "'à la carte'", "à la carte");
    }

    @Test void gbkRoundTrip() {
        roundTrip("gbk", "VARCHAR(100) CHARACTER SET gbk", "'中文'", "v", "中文",
                "'汉字测试'", "汉字测试");
    }

    @Test void utf8mb4RoundTrip() {
        roundTrip("utf8mb4", "VARCHAR(100) CHARACTER SET utf8mb4", "'café☕'", "v", "café☕",
                "'café☕😀'", "café☕😀");
    }

    @Test
    void jsonRoundTripsNormalizedText() throws Exception {
        Project project = null;
        long t0 = System.nanoTime();
        try {
            createPair("type_json", "JSON");
            insertRow("type_json", 1L, "'{\"k\":\"值\"}'");
            project = startBaseline("cdc-type-json", "type_json", 1L);
            // MySQL normalizes stored JSON (spacing/key order), so contains-assertions
            // on keys and values per the brief — never raw text equality.
            String afterInsert = (String) normalized("type_json", "v", 1L);
            assertThat(afterInsert).contains("\"k\"").contains("值");
            String afterUpdate = (String) updateAndAwaitChange("type_json", 1L,
                    "'{\"k2\":\"值2\",\"n\":42}'", "v", afterInsert);
            assertThat(afterUpdate).contains("k2").contains("值2").contains("42");
        } finally {
            if (project != null) {
                finish(project);
            }
            log.info("MEASURED type matrix json round trip: {} ms", ms(t0));
        }
    }

    @Test
    void timeExtremesSurviveRoundTrip() throws Exception {
        Project project = null;
        long t0 = System.nanoTime();
        try {
            createPair("type_time", "TIME");
            insertRow("type_time", 1L, "'838:59:59'");
            insertRow("type_time", 2L, "'-10:30:00'");
            project = newProject("cdc-type-time", cdcConfig("type_time"));
            final Long projectId = project.getId();   // lambda capture (project is reassigned)
            manager.ensureStarted(project);
            // The baseline full load reads TIME via ResultSet.getTime; Connector/J's
            // SqlTimeValueFactory REJECTS hours<0 or >=24 with a DataReadException. If
            // that parks the consumer, surface it immediately with the stored message
            // instead of a blind timeout.
            waitFor("time baseline settled", 90_000,
                    () -> baselineDone(projectId) || taskStatus(projectId) == TaskStatus.ERROR);
            SyncTask task = taskStore.find(projectId).orElseThrow();
            assertThat(task.getStatus())
                    .describedAs("baseline parked instead of loading; lastSyncResult=%s",
                            task.getLastSyncResult())
                    .isNotEqualTo(TaskStatus.ERROR);
            waitFor("time baseline rows on target", 30_000, () -> countTarget("type_time") == 2);
            // Server-side TIME_FORMAT only — these values must never be materialized as
            // java.sql.Time through the driver on either side.
            assertThat(normalized("type_time", "TIME_FORMAT(v, '%H:%i:%s')", 1L))
                    .isEqualTo("838:59:59");
            assertThat(normalized("type_time", "TIME_FORMAT(v, '%H:%i:%s')", 2L))
                    .isEqualTo("-10:30:00");
            // Stream leg: swap both extremes through UPDATE row events.
            assertThat(updateAndAwaitChange("type_time", 1L, "'-10:30:00'",
                    "TIME_FORMAT(v, '%H:%i:%s')", "838:59:59"))
                    .isEqualTo("-10:30:00");
            assertThat(updateAndAwaitChange("type_time", 2L, "'12:34:56'",
                    "TIME_FORMAT(v, '%H:%i:%s')", "-10:30:00"))
                    .isEqualTo("12:34:56");
        } finally {
            if (project != null) {
                finish(project);
            }
            log.info("MEASURED type matrix time round trip: {} ms", ms(t0));
        }
    }

    @Test
    void zeroDateCoercedToNullOnTarget() throws Exception {
        Project project = null;
        long t0 = System.nanoTime();
        try {
            createPair("type_zerodate", "DATE");
            project = newProject("cdc-type-zerodate", cdcConfig("type_zerodate"));
            final Long projectId = project.getId();   // lambda capture (project is reassigned)
            manager.ensureStarted(project);
            waitFor("zerodate baseline done (empty table)", 90_000, () -> baselineDone(projectId));
            // The zero-date row arrives through the STREAM, not the baseline: the
            // baseline reader runs on the production URL (zeroDateTimeBehavior default
            // EXCEPTION) and would throw on '0000-00-00' — while the path under test is
            // the binlog mapper's ZERO_DATE_SENTINEL coercion (Review Focus #3 邻域).
            insertRow("type_zerodate", 1L, "'0000-00-00'");
            waitFor("zero date coerced to NULL and counted", 30_000, () -> {
                boolean nullOnTarget = queryTarget(
                        "SELECT COUNT(*) c FROM type_zerodate WHERE id = 1 AND v IS NULL")
                        .get(0).get("c").equals(1L);
                CdcRuntimeStats s = manager.statsFor(projectId);
                return nullOnTarget && s != null
                        && ((Number) s.snapshot().get("zeroDateCoerced")).longValue() >= 1L;
            });
            // Update leg: zero → valid → zero. Identical-value UPDATEs log no binlog row
            // event, so each leg below is guaranteed stream traffic. Exact valid-date
            // semantics live in dateRoundTrip; here NULL/non-NULL transitions are the
            // contract. (onUpdate maps BOTH images, so the counter may exceed the
            // brief's floor — we log the actual and assert the specified ≥1.)
            try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
                st.executeUpdate("UPDATE type_zerodate SET v = '2026-10-10' WHERE id = 1");
            }
            waitFor("valid date streamed", 30_000, () -> normalized("type_zerodate", "v", 1L) != null);
            try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
                st.executeUpdate("UPDATE type_zerodate SET v = '0000-00-00' WHERE id = 1");
            }
            waitFor("back to NULL via stream", 30_000, () -> normalized("type_zerodate", "v", 1L) == null);
            CdcRuntimeStats s = manager.statsFor(projectId);
            assertThat(s).isNotNull();
            long coerced = ((Number) s.snapshot().get("zeroDateCoerced")).longValue();
            log.info("MEASURED zeroDateCoerced counter after insert + update round: {}", coerced);
            assertThat(coerced).as("zeroDateCoerced stat via manager.statsFor").isGreaterThanOrEqualTo(1L);
        } finally {
            if (project != null) {
                finish(project);
            }
            log.info("MEASURED type matrix zerodate round trip: {} ms", ms(t0));
        }
    }

    // ================= helpers =================

    /**
     * The common per-type shape: own table {@code type_<label>}, own project, one seeded
     * row through the baseline, one UPDATE through the live stream. Assertions compare a
     * server-side normalized scalar ({@code expr}) so no extreme value is ever
     * materialized as a Java object, and the update leg waits for the normalized value to
     * CHANGE before asserting its exact new value — a corrupted stream write then fails
     * with the actual value in the message instead of a blind timeout.
     */
    private void roundTrip(String label, String columnDdl, String insertLiteral, String expr,
                           Object expectedAfterInsert, String updateLiteral, Object expectedAfterUpdate) {
        String table = "type_" + label;
        long t0 = System.nanoTime();
        Project project = null;
        try {
            createPair(table, columnDdl);
            insertRow(table, 1L, insertLiteral);
            project = startBaseline("cdc-type-" + label, table, 1L);
            // The change-detection wait below compares against the ACTUAL baseline value,
            // not the expected literal: Objects.equals is type-sensitive and the driver's
            // boxing may differ from the literal's (BIT `v + 0` → Integer vs expected
            // Long), which would make the wait pass instantly and read a stale value.
            // Same expr → same column type → stable boxing, so actual-vs-actual is sound.
            Object baselineActual = normalized(table, expr, 1L);
            assertRoundTripValue(baselineActual, expectedAfterInsert,
                    "baseline value of " + table);
            Object afterUpdate = updateAndAwaitChange(table, 1L, updateLiteral, expr, baselineActual);
            assertRoundTripValue(afterUpdate, expectedAfterUpdate,
                    "streamed-update value of " + table);
        } catch (SQLException e) {
            throw new IllegalStateException("Type-matrix fixture I/O failed for " + table, e);
        } finally {
            if (project != null) {
                finish(project);
            }
            log.info("MEASURED type matrix {} round trip: {} ms", label, ms(t0));
        }
    }

    /**
     * Exact equality, tolerant of the driver's {@link Number} subtype choice: MySQL
     * {@code v + 0} over BIT came back as {@code Integer} where {@code Long} was expected
     * — same mathematical value, different JDBC boxing. Comparing through
     * {@code new BigDecimal(toString)} keeps full precision (Long.MIN_VALUE, DECIMAL
     * scale, float shortest-repr round-trip) while dropping the subtype sensitivity. No
     * tolerance is introduced: BigDecimal equality is exact.
     */
    private static void assertRoundTripValue(Object actual, Object expected, String description) {
        if (expected instanceof Number) {
            assertThat(actual).as(description).isInstanceOf(Number.class);
            assertThat(new BigDecimal(actual.toString()))
                    .as(description)
                    .isEqualTo(new BigDecimal(expected.toString()));
        } else {
            assertThat(actual).as(description).isEqualTo(expected);
        }
    }

    /** {@code (id BIGINT PK, v <columnDdl>)} on BOTH schemas; DROP first for idempotent reruns. */
    private void createPair(String table, String columnDdl) throws SQLException {
        String ddl = "CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, v " + columnDdl + ")";
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute(ddl);
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute(ddl);
        }
    }

    /** Inserts via SQL literal — the driver never encodes the value on the way in. */
    private void insertRow(String table, long id, String valueLiteral) throws SQLException {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO " + table + " VALUES (" + id + ", " + valueLiteral + ")");
        }
    }

    private Project startBaseline(String projectName, String table, long expectedRows) {
        Project project = newProject(projectName, cdcConfig(table));
        Long projectId = project.getId();
        manager.ensureStarted(project);
        waitFor("baseline done for " + table, 90_000, () -> baselineDone(projectId));
        waitFor("baseline rows on " + table, 30_000, () -> countTarget(table) == expectedRows);
        return project;
    }

    private boolean baselineDone(Long projectId) {
        return positionStore.find(projectId)
                .map(p -> Boolean.TRUE.equals(p.isBaselineDone()))
                .orElse(false);
    }

    /** One target-side scalar, normalized server-side; {@code null} for a missing row or SQL NULL. */
    private Object normalized(String table, String expr, long id) {
        List<Map<String, Object>> rows = queryTarget(
                "SELECT " + expr + " AS norm FROM " + table + " WHERE id = " + id);
        return rows.isEmpty() ? null : rows.get(0).get("norm");
    }

    /**
     * UPDATEs the source row, then waits for the normalized target value to differ from
     * {@code previous} — proof the stream event landed (a MySQL identical-value UPDATE
     * logs no row event, so every caller passes a genuinely new value) — and returns it.
     */
    private Object updateAndAwaitChange(String table, long id, String setLiteral,
                                        String expr, Object previous) throws SQLException {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.executeUpdate("UPDATE " + table + " SET v = " + setLiteral + " WHERE id = " + id);
        }
        waitFor("stream update visible on " + table, 30_000,
                () -> !Objects.equals(normalized(table, expr, id), previous));
        return normalized(table, expr, id);
    }

    private TaskStatus taskStatus(Long projectId) {
        return taskStore.find(projectId).map(SyncTask::getStatus).orElse(null);
    }

    /**
     * Teardown discipline: stop, then DISABLE (so the 1s reconcile/supervise ticks of the
     * shared context never relaunch the consumer), then evict both pools.
     */
    private void finish(Project project) {
        manager.ensureStopped(project.getId());
        disableProject(project);
        dataSourceManager.evict(project.getSourceDbId());
        dataSourceManager.evict(project.getTargetDbId());
    }

    private SyncConfig cdcConfig(String... tables) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        for (String t : tables) {
            config.getTables().add(t);
        }
        return config;
    }

    private static long ms(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
