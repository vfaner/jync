package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * The SQL the target executes in {@link com.qqmu.jync.model.EtlWriteMode#UPSERT} mode.
 *
 * <p>Identifiers here are deliberately unquoted, like every other statement the ETL runner
 * builds: the target table is created unquoted too, so both sides fold the same way and an
 * Oracle target created as {@code dst} is reachable as {@code dst}, not as {@code "dst"}.
 */
class EtlUpsertSqlTest {

    @Test
    void mysqlUsesOneStatementThatUpdatesEveryNonKeyColumn() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.MYSQL, "dst",
                List.of("id", "name", "amount"), List.of("id")))
                .isEqualTo("INSERT INTO dst (id, name, amount) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                        + " amount = VALUES(amount)");
    }

    @Test
    void mariadbSharesTheMysqlForm() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.MARIADB, "dst",
                List.of("id", "name"), List.of("id")))
                .contains("ON DUPLICATE KEY UPDATE name = VALUES(name)");
    }

    @Test
    void aKeyOnlyTableStillHasAValidMysqlStatement() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.MYSQL, "dst",
                List.of("id"), List.of("id")))
                .isEqualTo("INSERT INTO dst (id) VALUES (?)"
                        + " ON DUPLICATE KEY UPDATE id = id");
    }

    @Test
    void aTargetWithoutAPortableSingleStatementFormHasNone() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.POSTGRESQL, "dst",
                List.of("id", "name"), List.of("id"))).isNull();
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.ORACLE, "dst",
                List.of("id", "name"), List.of("id"))).isNull();
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.H2, "dst",
                List.of("id", "name"), List.of("id"))).isNull();
    }

    /**
     * Doris speaks the MySQL wire protocol but not MySQL's DML: it has no
     * ON DUPLICATE KEY UPDATE, so it must take the update-then-insert path.
     */
    @Test
    void dorisHasNoMysqlUpsertForm() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.DORIS, "dst",
                List.of("id", "name"), List.of("id"))).isNull();
    }

    @Test
    void theEmulatedUpdateSetsTheNonKeyColumnsAndMatchesOnTheKey() {
        assertThat(EtlUpsertSql.update("dst", List.of("id", "name", "amount"), List.of("id")))
                .isEqualTo("UPDATE dst SET name = ?, amount = ? WHERE id = ?");
    }

    @Test
    void aKeyOnlyTableHasNoUpdateStatement() {
        assertThat(EtlUpsertSql.update("dst", List.of("id"), List.of("id"))).isNull();
    }

    @Test
    void aCompositeKeyBindsInTheConfiguredOrder() {
        assertThat(EtlUpsertSql.update("dst", List.of("a", "b", "c"), List.of("b", "a")))
                .isEqualTo("UPDATE dst SET c = ? WHERE b = ? AND a = ?");
        assertThat(EtlUpsertSql.selectByKey("dst", List.of("b", "a")))
                .isEqualTo("SELECT 1 FROM dst WHERE b = ? AND a = ?");
    }

    @Test
    void theConfiguredListIsTrimmedAndBlankEntriesAreDropped() {
        assertThat(EtlUpsertSql.parse(" id,  tenant_id ,, "))
                .containsExactly("id", "tenant_id");
        assertThat(EtlUpsertSql.parse(null)).isEmpty();
        assertThat(EtlUpsertSql.parse("  ")).isEmpty();
    }

    @Test
    void keysResolveAgainstTheResultLabelsIgnoringCase() {
        assertThat(EtlUpsertSql.resolve(List.of("ID", "name"), List.of("id")))
                .containsExactly("ID");
    }

    @Test
    void aKeyThatIsNotInTheResultIsNamedInTheError() {
        assertThatThrownBy(() -> EtlUpsertSql.resolve(List.of("id"), List.of("id", "tenant")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
    }
}
