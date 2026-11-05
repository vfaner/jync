package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL 5.7 and 8.0 accept different SQL, and the difference is not cosmetic:
 *
 * <ul>
 *   <li>5.7 has no window functions, so the key-sampling query ({@code NTILE}) is a syntax
 *       error there and the full load has to run sequentially.</li>
 *   <li>5.7 has no expression defaults, so {@code DATE DEFAULT (CURRENT_DATE)} is rejected
 *       (valid from 8.0.13); the default has to be dropped instead.</li>
 * </ul>
 *
 * <p>An unknown version keeps today's behavior — attempt, and let the caller fall back when
 * the server rejects the statement — because guessing "old" would silently degrade a modern
 * server whose version could not be read.
 */
class MySqlDialectVersionTest {

    private static MySqlDialect on(DatabaseType type, Integer majorVersion) {
        return (MySqlDialect) new MySqlDialect().withServerMajorVersion(majorVersion)
                .forProduct(type);
    }

    @Test
    void mysql57CannotSplitKeysOnWindowFunctions() {
        assertThat(on(DatabaseType.MYSQL, 5).supportsWindowFunctions()).isFalse();
        assertThat(on(DatabaseType.MYSQL, 7).supportsWindowFunctions()).isFalse();
    }

    @Test
    void mysql80CanSplitKeysOnWindowFunctions() {
        assertThat(on(DatabaseType.MYSQL, 8).supportsWindowFunctions()).isTrue();
    }

    @Test
    void mariaDbTenCanSplitKeysOnWindowFunctions() {
        // MariaDB got window functions in 10.2.
        assertThat(on(DatabaseType.MARIADB, 10).supportsWindowFunctions()).isTrue();
    }

    @Test
    void aMariaDbPresetAgainstAModernServerStillSamplesKeys() {
        // The preset names the driver, not the server behind it: a "MariaDB" connection can
        // point at MySQL 8.0, which reports major 8. The server's number decides, so this
        // must not lose parallelism merely because the preset picked MariaDB's numbering.
        assertThat(on(DatabaseType.MARIADB, 8).supportsWindowFunctions()).isTrue();
    }

    @Test
    void aMySqlFamilyForkWithoutAKnownVersionAttemptsSampling() {
        // Doris and the other MySQL-protocol forks report a MySQL-shaped banner, so their
        // version is never resolved; attempting is the only answer that does not silently
        // drop parallelism on a product that does support window functions.
        assertThat(on(DatabaseType.DORIS, null).supportsWindowFunctions()).isTrue();
    }

    @Test
    void theVersionSurvivesForProduct() {
        // The factory calls withServerMajorVersion(...).forProduct(...) — a forProduct that
        // forgets the version would report a 5.7 server as modern.
        assertThat(new MySqlDialect().withServerMajorVersion(5)
                .forProduct(DatabaseType.MYSQL).supportsWindowFunctions()).isFalse();
    }

    @Test
    void aNonPositiveVersionIsUnknownNotAncient() {
        // JDBC metadata reports 0 when it cannot tell; treating that as "5.7" would silently
        // disable key sampling and drop DDL defaults on a server that may be 8.0.
        assertThat(on(DatabaseType.MYSQL, 0).supportsWindowFunctions()).isTrue();
        assertThat(on(DatabaseType.MYSQL, 0).columnDefinition(
                dateColumnWithCurrentTimestamp(), DatabaseType.POSTGRESQL))
                .contains("DATE DEFAULT (CURRENT_DATE)");
    }

    @Test
    void dateCurrentTimestampDefaultIsDroppedOnMysql57() {
        ColumnMeta d = dateColumnWithCurrentTimestamp();

        String ddl = on(DatabaseType.MYSQL, 5).columnDefinition(d, DatabaseType.POSTGRESQL);

        assertThat(ddl).contains("DATE").doesNotContain("DEFAULT");
    }

    @Test
    void dateCurrentTimestampDefaultStaysOnMysql80() {
        ColumnMeta d = dateColumnWithCurrentTimestamp();

        String ddl = on(DatabaseType.MYSQL, 8).columnDefinition(d, DatabaseType.POSTGRESQL);

        assertThat(ddl).contains("DATE DEFAULT (CURRENT_DATE)");
    }

    @Test
    void mariaDbTenKeepsTheExpressionDefault() {
        // MariaDB's release train numbers differently, so the "before 8.0" rule must not
        // catch it: 10.2+ accepts DATE DEFAULT (CURRENT_DATE), and the 10.x train is treated
        // as 10.2+ (10.0/10.1 are EOL and keep the pre-change attempt).
        ColumnMeta d = dateColumnWithCurrentTimestamp();

        String ddl = on(DatabaseType.MARIADB, 10).columnDefinition(d, DatabaseType.POSTGRESQL);

        assertThat(ddl).contains("DATE DEFAULT (CURRENT_DATE)");
    }

    @Test
    void unknownVersionKeepsTheCurrentDateDefault() {
        // Unknown is not "old": the statement is attempted as before.
        ColumnMeta d = dateColumnWithCurrentTimestamp();

        String ddl = on(DatabaseType.MYSQL, null).columnDefinition(d, DatabaseType.POSTGRESQL);

        assertThat(ddl).contains("DATE DEFAULT (CURRENT_DATE)");
    }

    private static ColumnMeta dateColumnWithCurrentTimestamp() {
        ColumnMeta d = new ColumnMeta();
        d.setName("D");
        d.setJdbcType(Types.DATE);
        d.setTypeName("DATE");
        d.setDefaultValue("CURRENT_TIMESTAMP");
        return d;
    }
}
