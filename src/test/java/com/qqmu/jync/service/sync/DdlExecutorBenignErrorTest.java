package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * A tolerated DDL failure must swallow exactly one thing: "the object was already in the
 * state I wanted" — absent for a tolerated DROP, present for an idempotent CREATE.
 *
 * <p>Two markers were broader than that and reported DDL that never ran as applied:
 *
 * <p><b>"cannot drop"</b> is how SQL Server opens its <em>missing table</em> message
 * ("Cannot drop the table 'x', because it does not exist") — but PostgreSQL and MySQL open
 * their <em>refused</em> drop messages the same way ("cannot drop table x because other
 * objects depend on it", "Cannot drop table 'child' referenced by a foreign key
 * constraint"). Swallowing those reports a DROP that did not happen as applied, and the
 * engine then deletes the table's snapshot: the change is never re-detected, so the target
 * keeps a table the source no longer has while the change log says it was dropped.
 *
 * <p><b>"duplicate"</b> covers MySQL's "Duplicate key name" (the index exists) but also
 * every vendor's unique-index-on-duplicate-data failure — "Duplicate entry ... for key",
 * ORA-01452, PostgreSQL's "is duplicated". Those mean the index was <em>not</em> created;
 * treating them as success certifies the table's shape and the missing unique index is
 * never retried.
 */
class DdlExecutorBenignErrorTest {

    private static DdlExecutor executorThrowing(String sql, String message, String sqlState,
                                                int errorCode) throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.execute(sql)).thenThrow(new SQLException(message, sqlState, errorCode));
        return new DdlExecutor(conn);
    }

    @Test
    void aPostgresDropRefusedByADependencyIsNotTreatedAsAMissingObject() throws Exception {
        DdlExecutor exec = executorThrowing("DROP TABLE orders",
                "ERROR: cannot drop table orders because other objects depend on it", "2BP01", 0);
        assertThatThrownBy(() -> exec.execute("DROP TABLE orders", true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("DROP TABLE orders");
    }

    @Test
    void aMysqlDropRefusedByAForeignKeyIsNotTreatedAsAMissingObject() throws Exception {
        DdlExecutor exec = executorThrowing("DROP TABLE child",
                "Cannot drop table 'child' referenced by a foreign key constraint 'fk_parent'.",
                "HY000", 1217);
        assertThatThrownBy(() -> exec.execute("DROP TABLE child", true))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void sqlServerMissingObjectWordingIsStillTolerated() throws Exception {
        // Msg 3701 — the benign case the broad marker was presumably meant for; it is
        // already covered by "does not exist".
        DdlExecutor exec = executorThrowing("DROP TABLE dbo.Nope",
                "Cannot drop the table 'dbo.Nope', because it does not exist or you do not"
                        + " have permission.", "S0001", 3701);
        assertThat(exec.execute("DROP TABLE dbo.Nope", true)).isFalse();
    }

    @Test
    void postgresUndefinedTableIsStillTolerated() throws Exception {
        DdlExecutor exec = executorThrowing("DROP TABLE gone",
                "ERROR: table \"gone\" does not exist", "42P01", 0);
        assertThat(exec.execute("DROP TABLE gone", true)).isFalse();
    }

    // —— the same over-broad match on the CREATE side ——

    @Test
    void oracleUniqueIndexOnDuplicateDataIsNotTreatedAsCreated() throws Exception {
        // ORA-01452: the index was NOT created — the target's own rows violate it. The
        // snapshot would otherwise record the unique index as present and never retry.
        DdlExecutor exec = executorThrowing("CREATE UNIQUE INDEX IX_EMAIL ON T (EMAIL)",
                "ORA-01452: cannot CREATE UNIQUE INDEX; duplicate keys found", "42000", 1452);
        assertThatThrownBy(() -> exec.executeIdempotentCreate(
                "CREATE UNIQUE INDEX IX_EMAIL ON T (EMAIL)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void mysqlUniqueIndexOnDuplicateDataIsNotTreatedAsCreated() throws Exception {
        DdlExecutor exec = executorThrowing("CREATE UNIQUE INDEX ix_email ON t (email)",
                "Duplicate entry 'a@b.c' for key 't.ix_email'", "23000", 1062);
        assertThatThrownBy(() -> exec.executeIdempotentCreate(
                "CREATE UNIQUE INDEX ix_email ON t (email)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void postgresUniqueIndexOnDuplicateDataIsNotTreatedAsCreated() throws Exception {
        DdlExecutor exec = executorThrowing("CREATE UNIQUE INDEX ix_email ON t (email)",
                "ERROR: could not create unique index \"ix_email\"\n  DETAIL: Key (email)"
                        + "=(a@b.c) is duplicated.", "23505", 0);
        assertThatThrownBy(() -> exec.executeIdempotentCreate(
                "CREATE UNIQUE INDEX ix_email ON t (email)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void mysqlDuplicateIndexNameIsStillTolerated() throws Exception {
        // Error 1061 is the index-already-there case the broad marker was covering.
        DdlExecutor exec = executorThrowing("CREATE INDEX ix_email ON t (email)",
                "Duplicate key name 'ix_email'", "42000", 1061);
        assertThat(exec.executeIdempotentCreate("CREATE INDEX ix_email ON t (email)")).isFalse();
    }

    @Test
    void mysqlDuplicateColumnNameIsStillTolerated() throws Exception {
        // Error 1060 is the column-already-there case: two cycles can race to add the same
        // missing column, and "already there" is the desired end state.
        DdlExecutor exec = executorThrowing("ALTER TABLE t ADD COLUMN gender VARCHAR(8)",
                "Duplicate column name 'gender'", "42S21", 1060);
        assertThat(exec.executeIdempotentCreate("ALTER TABLE t ADD COLUMN gender VARCHAR(8)"))
                .isFalse();
    }
}
