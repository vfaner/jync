package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * Whether a schema-wide table listing is authoritative — that is, whether a selected table
 * missing from it was really dropped. On MySQL and Oracle the listing is filtered by the
 * account's privileges, so a revoked single-table grant looks exactly like a drop: zero
 * columns on read, absent from the listing. Only an account that can see the whole schema
 * (schema owner, or schema-wide {@code SELECT}) makes the absence conclusive.
 *
 * <p>These checks run only when a selected table already read back with zero columns — the
 * healthy path never issues them.
 */
class SchemaListingCompletenessTest {

    private static final String SCHEMA = "shop";

    // ── defaults ─────────────────────────────────────────────────────────────────────

    @Test
    void theGenericReaderIsConservativeWithoutKnowingTheDictionary() {
        assertThat(new GenericMetadataReader()
                .schemaListingIsComplete(mock(Connection.class), SCHEMA)).isFalse();
    }

    @Test
    void theDb2ReaderStaysConservativeBecauseSyscatIsPrivilegeFiltered() {
        assertThat(new Db2MetadataReader()
                .schemaListingIsComplete(mock(Connection.class), SCHEMA)).isFalse();
    }

    @Test
    void postgresCatalogsCarryNoPrivilegeFilter() {
        // pg_catalog is readable by every user, so the listing already covers every table.
        assertThat(new PostgresMetadataReader().schemaListingIsComplete(null, SCHEMA)).isTrue();
    }

    // ── MySQL: SHOW GRANTS ───────────────────────────────────────────────────────────

    @Test
    void aSchemaWideGrantMakesTheMysqlListingAuthoritative() {
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT SELECT, INSERT ON `shop`.* TO `jync`@`%`", SCHEMA)).isTrue();
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT ALL PRIVILEGES ON `shop`.* TO `jync`@`%` WITH GRANT OPTION", SCHEMA)).isTrue();
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT SELECT ON *.* TO `jync`@`%`", SCHEMA)).isTrue();
        // A schema name containing a dot is quoted as one identifier.
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT SELECT ON `shop.archive`.* TO `jync`@`%`", "shop.archive")).isTrue();
    }

    @Test
    void aTableScopedOrUnrelatedGrantLeavesTheMysqlListingFiltered() {
        // The hazardous case: a table-level grant can be revoked, and the table then looks
        // dropped. It must never make the listing authoritative.
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT SELECT ON `shop`.`orders` TO `jync`@`%`", SCHEMA)).isFalse();
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT SELECT, INSERT ON `other`.* TO `jync`@`%`", SCHEMA)).isFalse();
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT USAGE ON *.* TO `jync`@`%`", SCHEMA)).isFalse();
        // Column-scoped grant: the target is a table, not the schema.
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT SELECT (`id`) ON `shop`.`orders` TO `jync`@`%`", SCHEMA)).isFalse();
        // Role assignment carries no ON clause.
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(
                "GRANT `reader`@`%` TO `jync`@`%`", SCHEMA)).isFalse();
        assertThat(MySQLMetadataReader.grantCoversWholeSchema(null, SCHEMA)).isFalse();
        assertThat(MySQLMetadataReader.grantCoversWholeSchema("not a grant", SCHEMA)).isFalse();
    }

    @Test
    void mysqlReadsShowGrantsAndFailsConservative() throws Exception {
        assertThat(new MySQLMetadataReader().schemaListingIsComplete(
                connectionGranting("GRANT SELECT ON `shop`.* TO `jync`@`%`"), SCHEMA)).isTrue();
        assertThat(new MySQLMetadataReader().schemaListingIsComplete(
                connectionGranting("GRANT SELECT ON `shop`.`orders` TO `jync`@`%`"), SCHEMA)).isFalse();

        Connection broken = mock(Connection.class);
        when(broken.createStatement()).thenThrow(new SQLException("grants unavailable"));
        assertThat(new MySQLMetadataReader().schemaListingIsComplete(broken, SCHEMA)).isFalse();
    }

    private static Connection connectionGranting(String grant) throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery("SHOW GRANTS")).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn(grant);
        return conn;
    }

    // ── Oracle: schema owner or SELECT ANY TABLE ─────────────────────────────────────

    @Test
    void theOracleSchemaOwnerSeesEveryOneOfItsTables() throws Exception {
        assertThat(new OracleMetadataReader()
                .schemaListingIsComplete(connectionQueried("SHOP", 0), SCHEMA)).isTrue();
    }

    @Test
    void selectAnyTableMakesTheOracleListingAuthoritative() throws Exception {
        assertThat(new OracleMetadataReader()
                .schemaListingIsComplete(connectionQueried("JYNC", 1), SCHEMA)).isTrue();
    }

    @Test
    void anOracleAccountWithTableGrantsOnlyLeavesTheListingFiltered() throws Exception {
        assertThat(new OracleMetadataReader()
                .schemaListingIsComplete(connectionQueried("JYNC", 0), SCHEMA)).isFalse();
    }

    @Test
    void anOracleDictionaryFailureIsConservative() throws Exception {
        Connection broken = mock(Connection.class);
        when(broken.prepareStatement(anyString())).thenThrow(new SQLException("no dictionary"));

        assertThat(new OracleMetadataReader().schemaListingIsComplete(broken, SCHEMA)).isFalse();
    }

    private static Connection connectionQueried(String user, int selectAnyTable) throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement userPs = mock(PreparedStatement.class);
        ResultSet userRs = mock(ResultSet.class);
        when(conn.prepareStatement(contains("FROM DUAL"))).thenReturn(userPs);
        when(userPs.executeQuery()).thenReturn(userRs);
        when(userRs.next()).thenReturn(true);
        when(userRs.getString(1)).thenReturn(user);

        PreparedStatement privPs = mock(PreparedStatement.class);
        ResultSet privRs = mock(ResultSet.class);
        when(conn.prepareStatement(contains("SESSION_PRIVS"))).thenReturn(privPs);
        when(privPs.executeQuery()).thenReturn(privRs);
        when(privRs.next()).thenReturn(true);
        when(privRs.getInt(1)).thenReturn(selectAnyTable);
        return conn;
    }

    // ── SQL Server: VIEW DEFINITION ──────────────────────────────────────────────────

    @Test
    void viewDefinitionMakesTheSqlServerMetadataComplete() throws Exception {
        assertThat(new SqlServerMetadataReader()
                .schemaListingIsComplete(connectionWithPerms(1), SCHEMA)).isTrue();
        assertThat(new SqlServerMetadataReader()
                .schemaListingIsComplete(connectionWithPerms(0), SCHEMA)).isFalse();
    }

    @Test
    void aSqlServerPermissionProbeFailureIsConservative() throws Exception {
        Connection broken = mock(Connection.class);
        when(broken.prepareStatement(anyString())).thenThrow(new SQLException("probe failed"));

        assertThat(new SqlServerMetadataReader().schemaListingIsComplete(broken, SCHEMA)).isFalse();
    }

    private static Connection connectionWithPerms(int allowed) throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(contains("HAS_PERMS_BY_NAME"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(allowed);
        return conn;
    }
}
