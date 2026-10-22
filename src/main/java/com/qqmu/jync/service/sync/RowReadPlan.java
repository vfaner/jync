package com.qqmu.jync.service.sync;

import java.util.List;

import com.qqmu.jync.service.lob.LobReadPolicy;

/**
 * The per-column facts a row read needs, resolved once per table instead of per row.
 *
 * <p>Every column's LOB mode depends only on its name, JDBC type and declared size; whether
 * the vendor-type normalizer applies depends only on its type name. Resolving both inside
 * the row loop lower-cased a column name and a type name on every value — an allocation per
 * column per row that measured at roughly 9% of a single-worker Oracle full load.
 *
 * <p>Built by the caller that knows the table (and therefore the policy); {@link
 * JdbcRowMapper#readRowInto(java.sql.ResultSet, RowReadPlan, com.qqmu.jync.model.DatabaseType,
 * java.util.Map, com.qqmu.jync.service.lob.LobSpillSession)} does no per-row lookups.
 */
final class RowReadPlan {

    private final List<String> columns;
    private final int[] types;
    private final String[] typeNames;
    private final LobReadPolicy.Mode[] modes;
    private final boolean[] vendorHandled;
    private final LobReadPolicy policy;

    private RowReadPlan(List<String> columns, int[] types, String[] typeNames,
                        LobReadPolicy.Mode[] modes, boolean[] vendorHandled,
                        LobReadPolicy policy) {
        this.columns = columns;
        this.types = types;
        this.typeNames = typeNames;
        this.modes = modes;
        this.vendorHandled = vendorHandled;
        this.policy = policy;
    }

    /**
     * @param policy per-table read policy, or null for the disabled one
     * @throws com.qqmu.jync.service.lob.LobLimitExceededException when a force-inline column
     *         is declared above its ceiling — thrown once, before any row is read
     */
    static RowReadPlan of(List<String> columns, List<Integer> types, List<String> typeNames,
                          LobReadPolicy policy) {
        LobReadPolicy effective = policy == null ? LobReadPolicy.disabled() : policy;
        int size = columns.size();
        int[] jdbcTypes = new int[size];
        String[] names = new String[size];
        LobReadPolicy.Mode[] modes = new LobReadPolicy.Mode[size];
        boolean[] vendorHandled = new boolean[size];
        for (int i = 0; i < size; i++) {
            int type = types.get(i);
            String typeName = typeNames == null ? null : typeNames.get(i);
            jdbcTypes[i] = type;
            names[i] = typeName;
            modes[i] = effective.modeFor(columns.get(i), type);
            vendorHandled[i] = JdbcValueNormalizer.handles(typeName);
        }
        return new RowReadPlan(columns, jdbcTypes, names, modes, vendorHandled, effective);
    }

    /** The policy the streamed branches consult for their inline threshold and spill mode. */
    LobReadPolicy policy() {
        return policy;
    }

    int size() {
        return columns.size();
    }

    String column(int index) {
        return columns.get(index);
    }

    int type(int index) {
        return types[index];
    }

    String typeName(int index) {
        return typeNames[index];
    }

    LobReadPolicy.Mode mode(int index) {
        return modes[index];
    }

    boolean vendorHandled(int index) {
        return vendorHandled[index];
    }
}
