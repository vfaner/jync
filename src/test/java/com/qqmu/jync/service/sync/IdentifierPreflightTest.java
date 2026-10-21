package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlServerDialect;

/**
 * A target name that cannot exist is refused before the cycle writes anything.
 *
 * <p>Left to the target, a 31-byte name on an 11g Oracle fails mid-load with ORA-00972, which
 * names neither the mapping that produced it nor the limit it broke. The preflight turns that
 * into one clear per-table error. The limits are the products' real ones — not one number for
 * everything — and a name that is merely close to a limit must not be refused: a false refusal
 * blocks a table that used to sync.
 */
class IdentifierPreflightTest {

    private static final String THIRTY = "T".repeat(30);
    private static final String THIRTY_ONE = "T".repeat(31);
    private static final String FORTY = "T".repeat(40);
    private static final String SIXTY_FOUR = "T".repeat(64);

    private static TableMeta table(String name, String... columns) {
        TableMeta table = new TableMeta();
        table.setName(name);
        for (String column : columns) {
            ColumnMeta meta = new ColumnMeta();
            meta.setName(column);
            meta.setJdbcType(Types.BIGINT);
            table.getColumns().add(meta);
        }
        return table;
    }

    private static List<IdentifierPreflight.Violation> check(DatabaseType type, SqlDialect dialect,
                                                             TableMeta... tables) {
        return IdentifierPreflight.check(type, dialect, List.of(tables), name -> name);
    }

    @Test
    void oracle11gStopsAtThirtyBytesAndAcceptsExactlyThirty() {
        assertThat(check(DatabaseType.ORACLE, new OracleDialect(11), table(THIRTY)))
                .as("a name at the limit is legal").isEmpty();

        assertThat(check(DatabaseType.ORACLE, new OracleDialect(11), table(THIRTY_ONE)))
                .singleElement()
                .satisfies(v -> assertThat(v.getMessage())
                        .contains(THIRTY_ONE).contains("31").contains("30"));
    }

    @Test
    void oracle12AndLaterAllowOneHundredTwentyEight() {
        assertThat(check(DatabaseType.ORACLE, new OracleDialect(19), table(FORTY))).isEmpty();
    }

    @Test
    void postgresStopsAtSixtyThree() {
        assertThat(check(DatabaseType.POSTGRESQL, new PostgresDialect(), table("T".repeat(63))))
                .isEmpty();
        assertThat(check(DatabaseType.POSTGRESQL, new PostgresDialect(), table(SIXTY_FOUR)))
                .hasSize(1);
    }

    @Test
    void mysqlStopsAtSixtyFourAndMariaDbFollowsTheSameFamily() {
        assertThat(check(DatabaseType.MYSQL, new MySqlDialect(), table(SIXTY_FOUR))).isEmpty();
        assertThat(check(DatabaseType.MYSQL, new MySqlDialect(), table("T".repeat(65))))
                .hasSize(1);
        assertThat(check(DatabaseType.MARIADB, new MySqlDialect(), table("T".repeat(65))))
                .hasSize(1);
    }

    @Test
    void mysqlCountsCharactersNotBytes() {
        // MySQL's documented limit is 64 characters, so 64 CJK names (192 bytes) still fit.
        assertThat(check(DatabaseType.MYSQL, new MySqlDialect(), table("表".repeat(64))))
                .as("a name at the character limit is legal however many bytes it takes")
                .isEmpty();
        assertThat(check(DatabaseType.MYSQL, new MySqlDialect(), table("表".repeat(65))))
                .hasSize(1);
    }

    @Test
    void sqlServerCountsCharactersNotBytes() {
        assertThat(check(DatabaseType.SQLSERVER, new SqlServerDialect(),
                table("表".repeat(128)))).isEmpty();
        assertThat(check(DatabaseType.SQLSERVER, new SqlServerDialect(),
                table("表".repeat(129)))).hasSize(1);
    }

    @Test
    void sqlServerAllowsOneHundredTwentyEight() {
        assertThat(check(DatabaseType.SQLSERVER, new SqlServerDialect(),
                table("T".repeat(128)))).isEmpty();
        assertThat(check(DatabaseType.SQLSERVER, new SqlServerDialect(),
                table("T".repeat(129)))).hasSize(1);
    }

    @Test
    void aLongColumnNameIsReportedAgainstItsTable() {
        List<IdentifierPreflight.Violation> violations = check(DatabaseType.ORACLE,
                new OracleDialect(11), table("ORDERS", "ID", THIRTY_ONE));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getTableName()).isEqualTo("ORDERS");
            assertThat(v.getMessage()).contains(THIRTY_ONE).contains("column");
        });
    }

    @Test
    void theMappedTargetNameIsWhatCountsNotTheSourceName() {
        List<IdentifierPreflight.Violation> violations = IdentifierPreflight.check(
                DatabaseType.ORACLE, new OracleDialect(11), List.of(table("SHORT", "ID")),
                name -> "A_VERY_LONG_TARGET_NAME_MAPPED_FROM_SHORT");

        assertThat(violations).singleElement()
                .satisfies(v -> assertThat(v.getMessage())
                        .contains("A_VERY_LONG_TARGET_NAME_MAPPED_FROM_SHORT"));

        assertThat(IdentifierPreflight.check(DatabaseType.ORACLE, new OracleDialect(11),
                List.of(table(THIRTY_ONE, "ID")), name -> "SHORT")).isEmpty();
    }

    @Test
    void multibyteNamesCountAsUtf8Bytes() {
        // Ten CJK characters are 30 bytes in UTF-8; eleven are 33 and do not fit.
        assertThat(check(DatabaseType.ORACLE, new OracleDialect(11), table("表".repeat(10))))
                .isEmpty();
        assertThat(check(DatabaseType.ORACLE, new OracleDialect(11), table("表".repeat(11))))
                .hasSize(1);
    }

    @Test
    void otherOracleFamilyProductsAreNotHeldToOraclesLimit() {
        // DM and YashanDB allow 128 bytes; the shared Oracle dialect is pinned to the 11g
        // branch for them, so applying Oracle's 30 here would refuse legal names.
        assertThat(check(DatabaseType.DM, new OracleDialect(11), table(FORTY))).isEmpty();
    }

    @Test
    void anUnknownTargetChecksNothing() {
        assertThat(check(DatabaseType.CUSTOM, new GenericSqlDialect(), table(FORTY))).isEmpty();
    }
}
