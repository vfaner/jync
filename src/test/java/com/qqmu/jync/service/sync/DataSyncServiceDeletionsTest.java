package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.GenericSqlDialect;

/**
 * Deletion detection compares key sets read from two different databases, so a key value has
 * to be rendered into canonical text before it is compared. When the two drivers disagree
 * about the Java type — byte[] identity hashes, decimal scale, temporal classes — a naive
 * {@code String.valueOf} comparison misreads every target row as "deleted at source" and
 * wipes the table.
 */
class DataSyncServiceDeletionsTest {

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:del_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:del_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        service = new DataSyncService(new SyncProperties());
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        source.close();
        target.close();
    }

    @Test
    void binaryKeysMatchAcrossReads() throws SQLException {
        // byte[]'s default toString is an identity hash that differs on every read; without
        // normalization every target row would look deleted and the whole table would go.
        exec(source, "CREATE TABLE BIN_T (K VARBINARY(16) PRIMARY KEY)");
        exec(target, "CREATE TABLE BIN_T (K VARBINARY(16) PRIMARY KEY)");
        exec(source, "INSERT INTO BIN_T VALUES (X'0102'), (X'0304')");
        exec(target, "INSERT INTO BIN_T VALUES (X'0102'), (X'0304'), (X'0506')");

        int deleted = service.syncDeletions(source, target, table("BIN_T", "K", 3), ctx);

        // Only the key that is genuinely absent from the source may be removed.
        assertThat(deleted).isEqualTo(1);
        assertThat(count("BIN_T")).isEqualTo(2);
    }

    @Test
    void decimalScaleDriftIsNotADeletion() throws SQLException {
        // The same value arriving as 1.50 on one side and 1.5 on the other must compare equal.
        exec(source, "CREATE TABLE NUM_T (K NUMERIC(10,2) PRIMARY KEY)");
        exec(target, "CREATE TABLE NUM_T (K NUMERIC(10,1) PRIMARY KEY)");
        exec(source, "INSERT INTO NUM_T VALUES (1.50)");
        exec(target, "INSERT INTO NUM_T VALUES (1.5)");

        int deleted = service.syncDeletions(source, target, table("NUM_T", "K", 1), ctx);

        assertThat(deleted).isZero();
        assertThat(count("NUM_T")).isEqualTo(1);
    }

    @Test
    void rowsGenuinelyGoneFromSourceAreStillDeleted() throws SQLException {
        // Normalization must not turn the feature off: a real deletion still propagates.
        exec(source, "CREATE TABLE ORD (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE ORD (ID BIGINT PRIMARY KEY)");
        exec(source, "INSERT INTO ORD VALUES (1), (2)");
        exec(target, "INSERT INTO ORD VALUES (1), (2), (3)");

        int deleted = service.syncDeletions(source, target, table("ORD", "ID", 3), ctx);

        assertThat(deleted).isEqualTo(1);
        assertThat(count("ORD")).isEqualTo(2);
    }

    @Test
    void keyPartsAreNormalizedByType() {
        assertThat(DataSyncService.keyPartOf(new byte[] {0x01, (byte) 0xAB})).isEqualTo("01ab");

        // Every numeric flavor lands on the same canonical decimal text.
        assertThat(DataSyncService.keyPartOf(new BigDecimal("1.50")))
                .isEqualTo(DataSyncService.keyPartOf(1.5d))
                .isEqualTo("1.5");
        assertThat(DataSyncService.keyPartOf(42L))
                .isEqualTo(DataSyncService.keyPartOf(new BigDecimal("42")))
                .isEqualTo(DataSyncService.keyPartOf(42));

        // The same instant read as Timestamp by one driver and LocalDateTime by the other.
        Timestamp ts = Timestamp.valueOf("2024-01-01 10:00:00");
        assertThat(DataSyncService.keyPartOf(ts))
                .isEqualTo(DataSyncService.keyPartOf(LocalDateTime.of(2024, 1, 1, 10, 0)));

        assertThat(DataSyncService.keyPartOf(null)).isEmpty();
        assertThat(DataSyncService.keyPartOf("abc")).isEqualTo("abc");
    }

    private TableMeta table(String name, String pk, long approximateRows) {
        TableMeta table = new TableMeta();
        table.setName(name);
        table.setPrimaryKeys(List.of(pk));
        // Below the comparison ceiling, so deletion detection actually runs.
        table.setApproximateRowCount(approximateRows);
        return table;
    }

    private long count(String tableName) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
