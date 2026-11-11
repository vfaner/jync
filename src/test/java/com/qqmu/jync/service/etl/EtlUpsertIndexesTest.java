package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Whether the target enforces uniqueness on exactly the configured upsert key.
 *
 * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE} only converges when a unique index on exactly
 * the key columns exists: a unique index on more columns, a non-unique one, or one belonging
 * to a table whose name merely matches the LIKE pattern cannot make a repeated key collide.
 */
class EtlUpsertIndexesTest {

    @Test
    void aUniqueIndexOnExactlyTheKeysCoversThem() throws SQLException {
        Connection target = target(new IndexRow("PRIMARY", "ID", "dst", true));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("id"))).isTrue();
    }

    @Test
    void theMatchIgnoresCase() throws SQLException {
        Connection target = target(new IndexRow("UK_ID", "id", "dst", true));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("ID"))).isTrue();
    }

    @Test
    void anIndexOnMoreColumnsThanTheKeysDoesNotCoverThem() throws SQLException {
        // A key that is only unique together with a second column can repeat with another
        // value of it — ON DUPLICATE KEY would then insert instead of update.
        Connection target = target(new IndexRow("UK_ID_NAME", "ID", "dst", true),
                new IndexRow("UK_ID_NAME", "NAME", "dst", true));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("id"))).isFalse();
    }

    @Test
    void anIndexOnFewerColumnsThanTheKeysDoesNotCoverThem() throws SQLException {
        Connection target = target(new IndexRow("UK_ID", "ID", "dst", true));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("id", "name")))
                .isFalse();
    }

    @Test
    void aNonUniqueIndexDoesNotCover() throws SQLException {
        Connection target = target(new IndexRow("IDX_ID", "ID", "dst", false));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("id"))).isFalse();
    }

    @Test
    void anIndexOfATableWhoseNameMerelyMatchesThePatternDoesNotAnswer() throws SQLException {
        // The table argument is a LIKE pattern: asking for dst also returns dst_old.
        Connection target = target(new IndexRow("PRIMARY", "ID", "dst_old", true));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("id"))).isFalse();
    }

    @Test
    void aFunctionalKeyPartDisqualifiesTheWholeIndex() throws SQLException {
        // MySQL reports a null COLUMN_NAME for an expression part, e.g. UNIQUE
        // ((lower(name)), id): dropping only that part would make the index look like a
        // unique index on the configured keys, which it is not.
        Connection target = target(new IndexRow("UK_NAME_EXPR", "NAME", "dst", true),
                new IndexRow("UK_NAME_EXPR", null, "dst", true));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("name"))).isFalse();
    }

    @Test
    void aPrefixIndexPartDoesNotCoverTheWholeColumn() throws SQLException {
        // UNIQUE KEY uk_email (email(32)) matches only the first 32 characters: two distinct
        // addresses that share that prefix must not be treated as the same key, or the
        // native upsert silently overwrites one with the other.
        Connection target = target(new IndexRow("UK_EMAIL", "EMAIL", "dst", true, 32));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("email"))).isFalse();
    }

    @Test
    void aFullColumnUniqueIndexStillCoversAlthoughAnotherIsPrefixOnly() throws SQLException {
        Connection target = target(new IndexRow("UK_EMAIL_PREFIX", "EMAIL", "dst", true, 16),
                new IndexRow("UK_EMAIL_FULL", "EMAIL", "dst", true, null));

        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("email"))).isTrue();
    }

    @Test
    void unreadableMetadataIsNotACover() throws SQLException {
        Connection target = mock(Connection.class);
        when(target.getMetaData()).thenThrow(new SQLException("dictionary unavailable"));

        // Guessing "covered" would duplicate rows on every run; the emulation is always safe.
        assertThat(EtlUpsertIndexes.coversKeys(target, "dst", List.of("id"))).isFalse();
    }

    // --- harness ------------------------------------------------------------------------

    private static Connection target(IndexRow... rows) throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("APP");
        ResultSet indexRows = mock(ResultSet.class);
        when(metadata.getIndexInfo(any(), any(), anyString(), eq(true), eq(true)))
                .thenReturn(indexRows);
        int[] cursor = {-1};
        when(indexRows.next()).thenAnswer(invocation -> ++cursor[0] < rows.length);
        when(indexRows.getShort("TYPE")).thenReturn(DatabaseMetaData.tableIndexOther);
        when(indexRows.getString(anyString())).thenAnswer(invocation -> {
            IndexRow row = rows[cursor[0]];
            String field = invocation.getArgument(0);
            return switch (field) {
                case "INDEX_NAME" -> row.index();
                case "COLUMN_NAME" -> row.column();
                case "TABLE_NAME" -> row.table();
                case "SUB_PART" -> row.subPart() == null ? null : row.subPart().toString();
                default -> null;
            };
        });
        when(indexRows.getBoolean("NON_UNIQUE")).thenAnswer(invocation -> !rows[cursor[0]].unique());
        return connection;
    }

    private record IndexRow(String index, String column, String table, boolean unique,
                            Integer subPart) {
        IndexRow(String index, String column, String table, boolean unique) {
            this(index, column, table, unique, null);
        }
    }
}
