package com.qqmu.jync.service.converter;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The "this row really is different" condition for an upsert that rewrites the row it matched.
 *
 * <p>MySQL answers 0 for an upsert whose values are already there, so the engine can tell a
 * no-op from a write. The MERGE-family targets and PostgreSQL's {@code ON CONFLICT DO UPDATE}
 * answer 1 — they cannot say whether the row was already identical, which is fine for
 * correctness (the end state is the same) but not for the change log: on a table whose cursor
 * carries a safety margin, the margin rows are replayed every cycle, and every replay is
 * reported as an update. Measured on Oracle: an idle MySQL→Oracle project logged an update
 * every cycle, while MySQL→MySQL stayed silent.
 *
 * <p>Adding this condition makes the target itself report 0 when nothing differs, so the
 * engine's existing "count == 0 means nothing moved" path covers every target, not MySQL alone.
 *
 * <p>Whether a condition may be added at all is decided by the caller, from the target
 * columns' real types: a column whose values cannot be compared (CLOB/BLOB/LONG, and the
 * vendor types that land in JDBC {@code OTHER}) makes this statement fail outright — Oracle
 * raises ORA-22848/ORA-00932 — so such a table keeps the plain upsert and its extra log
 * lines instead. The bind-type map the caller also passes cannot answer that question: it
 * carries the source's types, and a VARCHAR source landing in a CLOB target is a shape this
 * sync explicitly supports.
 */
final class UpsertChangeGuard {

    private UpsertChangeGuard() {
    }

    /**
     * @param updatable the non-key columns the matched branch assigns
     * @param left      qualified reference to the target row's column
     * @param right     qualified reference to the source row's column
     * @return a NULL-safe "differs" condition over every column, or null when there is
     *     nothing to guard (a table whose every column is part of the key)
     */
    static String condition(List<String> updatable, Function<String, String> left,
                            Function<String, String> right) {
        if (updatable.isEmpty()) {
            return null;
        }
        String differs = updatable.stream()
                .map(column -> differs(left.apply(column), right.apply(column)))
                .collect(Collectors.joining(" OR "));
        return "(" + differs + ")";
    }

    /**
     * NULL-safe inequality. A bare {@code a <> b} is NULL when either side is NULL, so a value
     * appearing or disappearing would look like "no difference" and the update would be skipped
     * — the one failure mode this guard must not have.
     */
    private static String differs(String left, String right) {
        return left + " <> " + right
                + " OR (" + left + " IS NULL AND " + right + " IS NOT NULL)"
                + " OR (" + left + " IS NOT NULL AND " + right + " IS NULL)";
    }
}
