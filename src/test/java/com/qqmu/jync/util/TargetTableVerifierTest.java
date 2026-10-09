package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * P2: an existing target table of the right name but wrong shape must be named as the blocker,
 * while safe cross-type differences (INT→BIGINT, VARCHAR→CLOB) must pass.
 */
class TargetTableVerifierTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:shape_verify;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    void identicalShapesPass() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, NAME VARCHAR(100))");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)));

        assertThat(reason).isNull();
    }

    @Test
    void aMissingColumnIsReported() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)));

        assertThat(reason).contains("missing column NAME");
    }

    @Test
    void aStringColumnBuiltAsNumericIsIncompatible() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, NAME INT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)));

        assertThat(reason).contains("column NAME", "incompatible type");
    }

    @Test
    void safeWideningWithinTheNumericFamilyPasses() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.INTEGER)));

        assertThat(reason).isNull();
    }

    @Test
    void aClobTargetAcceptsAVarcharSource() throws SQLException {
        createTarget("CREATE TABLE T (NOTE CLOB)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("NOTE", Types.VARCHAR)));

        assertThat(reason).isNull();
    }

    @Test
    void aTemporalColumnBuiltAsNumericIsIncompatible() throws SQLException {
        createTarget("CREATE TABLE T (WHEN_T INT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("WHEN_T", Types.TIMESTAMP)));

        assertThat(reason).contains("incompatible type");
    }

    private void createTarget(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T");
            st.execute(sql);
        }
    }

    private static ColumnMeta col(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }

    private static TableMeta source(ColumnMeta... columns) {
        TableMeta table = new TableMeta();
        table.setName("T");
        List<ColumnMeta> list = new ArrayList<>();
        for (ColumnMeta column : columns) {
            list.add(column);
        }
        table.setColumns(list);
        return table;
    }
}
