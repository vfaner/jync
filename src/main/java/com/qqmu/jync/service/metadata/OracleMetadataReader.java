package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * Oracle-family reader: Oracle, 达梦 (DM) and 崖山 (YashanDB), which keep the same
 * dictionary layout ({@code ALL_VIEWS}, {@code ALL_SOURCE}, {@code ALL_OBJECTS}).
 *
 * <p>Oracle has no catalogs, so the catalog argument is always null and the schema is the
 * owner. View text lives in {@code ALL_VIEWS.TEXT} and routine source in
 * {@code ALL_SOURCE}, which must be reassembled line by line.
 */
@Component
@Slf4j
public class OracleMetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.ORACLE
                || type == DatabaseType.DM
                || type == DatabaseType.YASHANDB;
    }

    @Override
    protected String catalogFor(Connection conn, String schema) {
        return null;
    }

    @Override
    protected String schemaPattern(String schema) {
        // Oracle stores unquoted identifiers folded to upper case.
        return schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
    }

    @Override
    public String resolveDefaultSchema(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT USER FROM DUAL")) {
            MetadataTimeouts.apply(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.debug("SELECT USER failed: {}", e.getMessage());
        }
        return super.resolveDefaultSchema(conn);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code ALL_TABLES} and {@code ALL_TAB_COLUMNS} are filtered by the account's object
     * privileges, and Oracle answers {@code ORA-00942} for a dropped table and for a revoked
     * grant alike. Two accounts still see the whole schema: its owner, and one holding
     * {@code SELECT ANY TABLE} (role-granted privileges are enabled in the session and
     * appear in {@code SESSION_PRIVS}). Any other account keeps the target table.
     */
    @Override
    public boolean schemaListingIsComplete(Connection conn, String schema) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT USER FROM DUAL")) {
            MetadataTimeouts.apply(ps);
            try (ResultSet rs = ps.executeQuery()) {
                String user = rs.next() ? rs.getString(1) : null;
                if (user != null && user.equalsIgnoreCase(schema)) {
                    return true;                // the schema owner sees all of its tables
                }
            }
            try (PreparedStatement priv = conn.prepareStatement(
                    "SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE = 'SELECT ANY TABLE'")) {
                MetadataTimeouts.apply(priv);
                try (ResultSet rs = priv.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            log.debug("Could not probe Oracle schema visibility; drops stay conservative: {}",
                    e.getMessage());
        }
        return false;
    }

    /**
     * Adds the {@code ROWDEPENDENCIES} probe to the generic table read.
     *
     * <p>Only real Oracle is whitelisted: 达梦 and 崖山 mimic the dictionary layout (this
     * reader serves all three), but {@code ORA_ROWSCN} row-level semantics are an Oracle
     * guarantee, so on the look-alikes the flag stays null and the cursor resolver never
     * offers the SCN strategy. The probe failure is non-fatal for the same reason — an
     * unreadable dictionary must not fail the whole metadata read.
     */
    @Override
    protected TableMeta readTable(Connection conn, String schema, String tableName,
                                  boolean includeDetail, boolean includeForeignKeys)
            throws SQLException {
        TableMeta table = super.readTable(conn, schema, tableName, includeDetail,
                includeForeignKeys);
        if (table != null) {
            if (includeDetail) {
                // The dictionary is the only reliable source of comments here: ojdbc returns
                // REMARKS as null unless the connection was opened with remarksReporting=true,
                // and 达梦/崖山 follow the same default. Comments feed only the structural
                // diff, so the reduced data read skips both queries.
                readComments(conn, schema, tableName, table);
            }
            // The row-level probe stays in both modes: CursorStrategyResolver chooses the SCN
            // cursor strategy from it, and the data path runs with structure sync off too.
            if (isOracleProduct(conn)) {
                table.setRowLevelScn(probeRowDependencies(conn, schema, tableName));
            }
        }
        return table;
    }

    /**
     * Fills table and column comments from {@code ALL_TAB_COMMENTS} / {@code ALL_COL_COMMENTS},
     * which all three Oracle-family products implement. A comment found here wins over the
     * driver's REMARKS; a missing or unreadable dictionary leaves the existing values alone
     * and never fails the metadata read.
     */
    void readComments(Connection conn, String schema, String tableName, TableMeta table) {
        String tableSql = "SELECT COMMENTS FROM ALL_TAB_COMMENTS "
                + "WHERE OWNER = NVL(?, USER) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(tableSql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, tableName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String comment = rs.getString(1);
                    if (comment != null && !comment.isBlank()) {
                        table.setRemarks(comment);
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("ALL_TAB_COMMENTS read failed for {}: {}", tableName, e.getMessage());
        }

        if (table.getColumns() == null || table.getColumns().isEmpty()) {
            return;
        }
        String columnSql = "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS "
                + "WHERE OWNER = NVL(?, USER) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(columnSql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, tableName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String comment = rs.getString(2);
                    if (name == null || comment == null || comment.isBlank()) {
                        continue;
                    }
                    table.column(name).ifPresent(col -> col.setRemarks(comment));
                }
            }
        } catch (SQLException e) {
            log.debug("ALL_COL_COMMENTS read failed for {}: {}", tableName, e.getMessage());
        }
    }

    private boolean isOracleProduct(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("oracle");
        } catch (SQLException e) {
            log.debug("Could not read database product name: {}", e.getMessage());
            return false;
        }
    }

    /** {@code ALL_TABLES.DEPENDENCIES}: ENABLED = row-level SCN, DISABLED = block-level. */
    private Boolean probeRowDependencies(Connection conn, String schema, String tableName) {
        String sql = "SELECT DEPENDENCIES FROM ALL_TABLES "
                + "WHERE OWNER = NVL(?, USER) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, tableName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "ENABLED".equalsIgnoreCase(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.debug("ROWDEPENDENCIES probe failed for {}: {}", tableName, e.getMessage());
        }
        return null;
    }

    @Override
    public ViewMeta readView(Connection conn, String schema, String viewName) throws SQLException {
        ViewMeta view = new ViewMeta();
        view.setName(viewName);
        view.setSchema(schema);
        String sql = "SELECT TEXT FROM ALL_VIEWS WHERE OWNER = NVL(?, USER) AND VIEW_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, viewName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // TEXT is LONG; the driver surfaces it as a string here.
                    view.setDefinition(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read Oracle view text for {}: {}", viewName, e.getMessage());
        }
        return view;
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) throws SQLException {
        ProcedureMeta proc = super.readProcedure(conn, schema, procedureName);

        String typeSql = "SELECT OBJECT_TYPE FROM ALL_OBJECTS "
                + "WHERE OWNER = NVL(?, USER) AND OBJECT_NAME = ? "
                + "AND OBJECT_TYPE IN ('PROCEDURE','FUNCTION')";
        try (PreparedStatement ps = conn.prepareStatement(typeSql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, procedureName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    proc.setRoutineType(rs.getString(1));
                }
            }
        }

        // ALL_SOURCE holds one row per source line; LINE ordering must be preserved.
        String srcSql = "SELECT TEXT FROM ALL_SOURCE WHERE OWNER = NVL(?, USER) "
                + "AND NAME = ? AND TYPE = ? ORDER BY LINE";
        StringBuilder body = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(srcSql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, procedureName.toUpperCase(Locale.ROOT));
            ps.setString(3, proc.getRoutineType() == null ? "PROCEDURE" : proc.getRoutineType());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null) {
                        body.append(line);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read Oracle source for {}: {}", procedureName, e.getMessage());
        }
        if (body.length() > 0) {
            proc.setDefinition(body.toString());
        }
        return proc;
    }

    @Override
    public long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException {
        String sql = "SELECT NUM_ROWS FROM ALL_TABLES WHERE OWNER = NVL(?, USER) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, tableName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long rows = rs.getLong(1);
                    // NUM_ROWS is null until statistics have been gathered.
                    if (!rs.wasNull() && rows > 0) {
                        return rows;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("ALL_TABLES estimate failed for {}: {}", tableName, e.getMessage());
        }
        return super.estimateRowCount(conn, schema, tableName);
    }

    @Override
    public Map<String, MetadataReader.TableStat> estimateTableStats(Connection conn, String schema) {
        // NUM_ROWS and AVG_ROW_LEN come from the same dictionary row, so the byte estimate
        // costs nothing extra. Both are null until the table has been analyzed, and the product
        // is selected through ALL_TABLES so a schema the account cannot see simply returns
        // fewer rows rather than failing the whole listing.
        return queryTableStats(conn,
                "SELECT TABLE_NAME, NUM_ROWS AS ROW_EST,"
                        + " NUM_ROWS * AVG_ROW_LEN AS TOTAL_BYTES"
                        + " FROM ALL_TABLES WHERE OWNER = NVL(?, USER)",
                upperOrNull(schema));
    }

    private String upperOrNull(String s) {
        return s == null || s.isBlank() ? null : s.toUpperCase(Locale.ROOT);
    }

    /**
     * Finds autoincrement columns JDBC's {@code IS_AUTOINCREMENT} misses on Oracle-family
     * servers:
     * <ul>
     *   <li>12c+ identity columns listed in {@code ALL_TAB_IDENTITY_COLS} (drivers older than
     *       the server may leave IS_AUTOINCREMENT empty);</li>
     *   <li>the pre-12c idiom — an enabled BEFORE-INSERT row trigger that pulls a sequence's
     *       NEXTVAL into {@code :NEW.col}.</li>
     * </ul>
     * Each probe degrades independently: a missing dictionary view (11g, or a look-alike that
     * does not expose it) only disables that probe.
     */
    @Override
    protected void enrichAutoIncrementColumns(Connection conn, String schema, TableMeta table) {
        List<ColumnMeta> columns = table.getColumns();
        if (columns == null || columns.isEmpty()) {
            return;
        }
        Set<String> identityColumns = new LinkedHashSet<>();
        identityColumns.addAll(queryIdentityColumns(conn, schema, table.getName()));
        identityColumns.addAll(identityColumnsFromTriggers(
                columnNamesUpperCase(columns), queryInsertTriggerBodies(conn, schema, table.getName())));
        if (identityColumns.isEmpty()) {
            return;
        }
        for (ColumnMeta column : columns) {
            if (column.getName() != null
                    && identityColumns.contains(column.getName().toUpperCase(Locale.ROOT))) {
                column.setAutoIncrement(true);
            }
        }
    }

    private static List<String> columnNamesUpperCase(List<ColumnMeta> columns) {
        return columns.stream()
                .map(ColumnMeta::getName)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.toUpperCase(Locale.ROOT))
                .toList();
    }

    /** {@code ALL_TAB_IDENTITY_COLS} (12c+); empty on servers without the view. */
    private Set<String> queryIdentityColumns(Connection conn, String schema, String tableName) {
        Set<String> columns = new LinkedHashSet<>();
        String sql = "SELECT COLUMN_NAME FROM ALL_TAB_IDENTITY_COLS "
                + "WHERE OWNER = NVL(?, USER) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, tableName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null) {
                        columns.add(name.toUpperCase(Locale.ROOT));
                    }
                }
            }
        } catch (SQLException e) {
            // ORA-00942: view absent on 11g or on look-alikes that do not provide it.
            log.debug("ALL_TAB_IDENTITY_COLS probe failed for {}: {}", tableName, e.getMessage());
        }
        return columns;
    }

    /**
     * Bodies of enabled BEFORE-INSERT triggers attached to the table. Timing is read from
     * TRIGGER_TYPE ({@code BEFORE ...}) rather than the BEFORE_ROW flag: measured on 26ai,
     * the BEFORE_STATEMENT/BEFORE_ROW/AFTER_* columns are left 'NO' even for a trigger whose
     * TRIGGER_TYPE is 'BEFORE EACH ROW'. This stays precise anyway — a statement-level
     * trigger cannot reference :NEW (it would not compile), so the {@code :NEW.col + NEXTVAL}
     * body heuristic alone admits only row-level before-insert fillers. On a look-alike whose
     * ALL_TRIGGERS lacks TRIGGER_TYPE the whole query fails and this probe silently degrades.
     */
    private List<String> queryInsertTriggerBodies(Connection conn, String schema, String tableName) {
        List<String> bodies = new ArrayList<>();
        String sql = "SELECT TRIGGER_BODY FROM ALL_TRIGGERS "
                + "WHERE OWNER = NVL(?, USER) AND TABLE_NAME = ? AND STATUS = 'ENABLED' "
                + "AND INSTR(UPPER(TRIGGERING_EVENT), 'INSERT') > 0 "
                + "AND UPPER(TRIGGER_TYPE) LIKE 'BEFORE%'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, tableName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // TRIGGER_BODY is LONG; the driver surfaces it as a string here.
                    String body = rs.getString(1);
                    if (body != null && !body.isBlank()) {
                        bodies.add(body);
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("ALL_TRIGGERS identity probe failed for {}: {}", tableName, e.getMessage());
        }
        return bodies;
    }

    private static final Pattern NEXTVAL = Pattern.compile("\\.NEXTVAL", Pattern.CASE_INSENSITIVE);

    /**
     * Heuristic behind trigger-based autoincrement detection: among {@code columnNames}, a
     * column is autoincrement when an enabled BEFORE-INSERT trigger body both references a
     * sequence ({@code seq.NEXTVAL}) and assigns it into {@code :NEW.col} — via
     * {@code SELECT ... INTO :NEW.col} or {@code :NEW.col := seq.NEXTVAL}. Audit triggers that
     * touch {@code :NEW} without a sequence, and bodies without NEXTVAL, match nothing.
     *
     * @param columnNames    candidate column names, used verbatim as the result set members
     * @param triggerBodies enabled BEFORE-INSERT trigger source bodies, nulls tolerated
     * @return columns (canonical spelling from {@code columnNames}) filled from a sequence
     */
    public static Set<String> identityColumnsFromTriggers(List<String> columnNames,
                                                          List<String> triggerBodies) {
        Set<String> marked = new LinkedHashSet<>();
        if (columnNames == null || columnNames.isEmpty() || triggerBodies == null) {
            return marked;
        }
        for (String body : triggerBodies) {
            if (body == null || body.isBlank() || !NEXTVAL.matcher(body).find()) {
                continue;
            }
            for (String column : columnNames) {
                if (column == null || column.isBlank() || marked.contains(column)) {
                    continue;
                }
                String quoted = Pattern.quote(column);
                // SELECT seq.NEXTVAL INTO :NEW.col  (optionally double-quoted)
                Pattern into = Pattern.compile(
                        "INTO\\s+:NEW\\.\"?" + quoted + "\"?(?![A-Z0-9_$#])",
                        Pattern.CASE_INSENSITIVE);
                // :NEW.col := seq.NEXTVAL
                Pattern assign = Pattern.compile(
                        ":NEW\\.\"?" + quoted + "\"?\\s*:=[^;]*\\.NEXTVAL",
                        Pattern.CASE_INSENSITIVE);
                if (into.matcher(body).find() || assign.matcher(body).find()) {
                    marked.add(column);
                }
            }
        }
        return marked;
    }
}
