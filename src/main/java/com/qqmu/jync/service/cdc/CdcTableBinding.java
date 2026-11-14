package com.qqmu.jync.service.cdc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.VendorTypeMap;

import lombok.Getter;

/**
 * Everything the CDC pipeline knows about one tracked source table: metadata, per-column
 * charsets (binlog ships raw bytes in the column's own charset), the mapped target name,
 * and whether row events can be applied at all (no PK => updates/deletes cannot locate
 * the row; task book B4 keeps such tables on one-shot seeding and the UI marks them).
 */
@Getter
public final class CdcTableBinding {

    private final String database;
    private final String table;
    private final TableMeta meta;
    private final String targetTable;
    /** Column name (upper-case) -> MySQL charset name, e.g. "utf8mb4"; absent = binary/unknown. */
    private final Map<String, String> charsetByUpperColumn;
    /**
     * Column name (upper-case) -> ENUM/SET label list. The binlog only carries the ordinal
     * (ENUM) or bitmask (SET); the labels come from information_schema at binding time.
     */
    private final Map<String, CdcEnumDefinition> enumByUpperColumn;
    private final boolean applicable;
    /**
     * SHA-256 over the ordered column names this binding decodes with; {@code null} when the
     * table could not be read. A binlog row image is positional, so this is the only thing
     * that can tell a later restart "the catalog is no longer the schema those rows were
     * written with" — see {@link CdcSchemaDrift}.
     */
    private final String layoutSignature;
    /**
     * {@link #columnNames()} is a per-row call on several paths, and LogMiner's redo parser
     * calls {@link #typeByColumn()} once per parsed row. Both are pure functions of
     * {@link #meta}, which is a snapshot — a layout change rebuilds the binding — so the
     * first caller pays and everyone after reuses one immutable copy.
     */
    private volatile List<String> columnNames;
    private volatile Map<String, Integer> typeByColumn;
    /**
     * Per-column decode facts in row-image order, built once: the charset, ENUM/SET labels and
     * type-name tests used to be re-resolved (each with a fresh upper-cased string) for every
     * column of every row.
     */
    private volatile List<CdcRowMapper.ColumnPlan> columnPlans;

    private CdcTableBinding(String database, String table, TableMeta meta, String targetTable,
                            Map<String, String> charsetByUpperColumn,
                            Map<String, CdcEnumDefinition> enumByUpperColumn, boolean applicable) {
        this.database = database;
        this.table = table;
        this.meta = meta;
        this.targetTable = targetTable;
        this.charsetByUpperColumn = charsetByUpperColumn;
        this.enumByUpperColumn = enumByUpperColumn;
        this.applicable = applicable;
        this.layoutSignature = layoutSignature(meta);
    }

    public static CdcTableBinding of(String database, String table, TableMeta meta,
                                     String targetTable, Map<String, String> charsetByUpperColumn) {
        return of(database, table, meta, targetTable, charsetByUpperColumn, null);
    }

    public static CdcTableBinding of(String database, String table, TableMeta meta,
                                     String targetTable, Map<String, String> charsetByUpperColumn,
                                     Map<String, CdcEnumDefinition> enumByUpperColumn) {
        boolean applicable = meta != null && meta.hasPrimaryKey();
        return new CdcTableBinding(database, table, meta, targetTable,
                charsetByUpperColumn == null ? new LinkedHashMap<>() : charsetByUpperColumn,
                enumByUpperColumn == null ? new LinkedHashMap<>() : enumByUpperColumn,
                applicable);
    }

    /** Label definition of one ENUM/SET column; null for every other column. */
    public CdcEnumDefinition enumDefinition(String column) {
        return column == null ? null : enumByUpperColumn.get(column.toUpperCase(Locale.ROOT));
    }

    /** Ordered column names of the binding's schema snapshot; immutable and cached. */
    public List<String> columnNames() {
        List<String> names = columnNames;
        if (names == null) {
            List<String> built = new ArrayList<>(meta.getColumns().size());
            for (ColumnMeta column : meta.getColumns()) {
                built.add(column.getName());
            }
            names = List.copyOf(built);
            columnNames = names;
        }
        return names;
    }

    public List<String> pkNames() {
        return meta.getPrimaryKeys();
    }

    /** Source JDBC type per column; immutable and cached. */
    public Map<String, Integer> typeByColumn() {
        Map<String, Integer> types = typeByColumn;
        if (types == null) {
            Map<String, Integer> built = new LinkedHashMap<>();
            for (ColumnMeta column : meta.getColumns()) {
                built.put(column.getName(), column.getJdbcType());
            }
            types = Collections.unmodifiableMap(built);
            typeByColumn = types;
        }
        return types;
    }

    /**
     * Per-column decode facts in row-image order, resolved once from this snapshot.
     *
     * <p>{@link CdcRowMapper#mapRow} runs for every row of every transaction; resolving the
     * charset, the ENUM/SET labels and the JSON / char-like type tests there upper-cased the
     * column name and the type name several times per cell. A layout change rebuilds the
     * binding, so the plan cannot outlive the schema it describes.
     */
    public List<CdcRowMapper.ColumnPlan> columnPlans() {
        List<CdcRowMapper.ColumnPlan> plans = columnPlans;
        if (plans == null) {
            List<ColumnMeta> columns = meta == null || meta.getColumns() == null
                    ? List.of() : meta.getColumns();
            List<CdcRowMapper.ColumnPlan> built = new ArrayList<>(columns.size());
            for (ColumnMeta column : columns) {
                String name = column.getName();
                String upper = name == null ? "" : name.toUpperCase(Locale.ROOT);
                built.add(new CdcRowMapper.ColumnPlan(column,
                        charsetByUpperColumn.get(upper), enumByUpperColumn.get(upper),
                        CdcRowMapper.isJsonType(column.getTypeName()),
                        CdcRowMapper.isCharLikeType(column.getJdbcType(), column.getTypeName())));
            }
            plans = List.copyOf(built);
            columnPlans = plans;
        }
        return plans;
    }

    /**
     * Bind types as the target dialect wants them. A source type is not always the target's
     * bind type: a vendor code (SQL Server -157 {@code geometry}) is not a code every
     * target driver accepts in {@code setNull}, and Oracle renders a text LOB placeholder
     * as CLOB so nulls carry a type the driver accepts and large values leave the LONG
     * path. Canonicalize the vendor type first, then map it for the target.
     */
    public Map<String, Integer> typeByColumn(SqlDialect targetDialect,
                                             DatabaseType sourceType) {
        Map<String, Integer> types = new LinkedHashMap<>();
        for (ColumnMeta column : meta.getColumns()) {
            types.put(column.getName(), targetDialect.bindJdbcType(column, sourceType,
                    VendorTypeMap.bindType(sourceType, column.getJdbcType(),
                            column.getTypeName())));
        }
        return types;
    }

    /**
     * Target-dialect DDL declaration per column, mirroring {@link #typeByColumn}. Dialects
     * whose native upsert needs typed parameter markers (DB2 MERGE: untyped markers are
     * re-resolved across the ON / UPDATE / INSERT contexts and disagree, SQL0418 / 42610)
     * wrap each placeholder in {@code CAST(? AS <declaration>)}. Columns the dialect maps
     * to nothing castable are simply absent from the map and keep an untyped marker.
     */
    public Map<String, String> declarationByColumn(SqlDialect targetDialect,
                                                   DatabaseType sourceType) {
        Map<String, String> declarations = new LinkedHashMap<>();
        for (ColumnMeta column : meta.getColumns()) {
            String mapped = targetDialect.mapType(column, sourceType);
            String castable = mapped == null ? null : targetDialect.castType(mapped);
            if (castable != null) {
                declarations.put(column.getName(), castable);
            }
        }
        return declarations;
    }

    /**
     * Fingerprint of the ordered column list, case-folded (MySQL column names are
     * case-insensitive). Names only on purpose: a type change does not move a positional
     * value into another column, while add / drop / rename / reorder all change the ordered
     * name list. Null when the table has no metadata.
     */
    public static String layoutSignature(TableMeta meta) {
        if (meta == null || meta.getColumns() == null) {
            return null;
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
        for (ColumnMeta column : meta.getColumns()) {
            String name = column == null || column.getName() == null
                    ? "" : column.getName().toUpperCase(Locale.ROOT);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = Character.forDigit((bytes[i] >> 4) & 0xF, 16);
            out[i * 2 + 1] = Character.forDigit(bytes[i] & 0xF, 16);
        }
        return new String(out);
    }
}
