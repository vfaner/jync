package com.synctool.service.monitor;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.synctool.config.SyncProperties;
import com.synctool.dto.SyncConfig;
import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.TableMeta;

import lombok.extern.slf4j.Slf4j;

/**
 * Chooses the incremental-detection strategy for a table.
 *
 * <p>Resolution order, most to least reliable:
 * <ol>
 *   <li>An explicit column configured by the user — always honoured, since the user knows
 *       the schema's semantics better than any heuristic.
 *   <li>A column whose name matches a last-modified convention and whose type is temporal.
 *       This is the only case that reliably catches updates.
 *   <li>A creation-timestamp column, which catches inserts but not updates.
 *   <li>A single-column integral primary key, which catches inserts only.
 *   <li>Full comparison, if the table is small enough to afford it.
 *   <li>Nothing — reported as such rather than silently skipped.
 * </ol>
 */
@Service
@Slf4j
public class CursorStrategyResolver {

    private final SyncProperties properties;

    public CursorStrategyResolver(SyncProperties properties) {
        this.properties = properties;
    }

    public CursorStrategy resolve(TableMeta table, SyncConfig config) {
        String configured = config.cursorColumnFor(table.getName());
        if (configured != null && !configured.isBlank()) {
            Optional<ColumnMeta> col = table.column(configured);
            if (col.isPresent()) {
                ColumnMeta c = col.get();
                if (CursorStrategy.isTemporalType(c.getJdbcType())) {
                    return CursorStrategy.timestamp(c.getName(), c.getJdbcType(),
                            "Configured by user");
                }
                if (CursorStrategy.isIntegralType(c.getJdbcType())) {
                    return CursorStrategy.identity(c.getName(), c.getJdbcType(),
                            "Configured by user (numeric: inserts only)");
                }
                log.warn("Configured cursor column {}.{} has an unusable type; falling back",
                        table.getName(), configured);
            } else {
                log.warn("Configured cursor column {}.{} does not exist; falling back",
                        table.getName(), configured);
            }
        }

        // A conventional last-modified column: catches inserts and updates.
        Optional<ColumnMeta> modified = findByHints(table, CursorStrategy.TIMESTAMP_HINTS, true);
        if (modified.isPresent()) {
            ColumnMeta c = modified.get();
            return CursorStrategy.timestamp(c.getName(), c.getJdbcType(),
                    "Auto-detected last-modified column");
        }

        // A creation timestamp: inserts only, but still far cheaper than full comparison.
        Optional<ColumnMeta> created = findByHints(table, CursorStrategy.CREATE_TIME_HINTS, true);
        if (created.isPresent()) {
            ColumnMeta c = created.get();
            return CursorStrategy.identity(c.getName(), c.getJdbcType(),
                    "Auto-detected creation timestamp (updates to existing rows are not detected)");
        }

        // A monotonic integral primary key: inserts only.
        for (ColumnMeta c : table.getColumns()) {
            if (CursorStrategy.isSingleIntegralPk(table, c)) {
                return CursorStrategy.identity(c.getName(), c.getJdbcType(),
                        "Single numeric primary key (updates to existing rows are not detected)");
            }
        }

        // Fall back to re-reading the table, but only while it stays small enough that doing
        // so every poll is not pathological.
        long rows = table.getApproximateRowCount() == null ? -1 : table.getApproximateRowCount();
        if (rows >= 0 && rows <= properties.getFullCompareMaxRows()) {
            return CursorStrategy.fullCompare(
                    "No cursor column; table has ~" + rows + " rows so it is fully compared");
        }

        return CursorStrategy.none("No timestamp or numeric key column, and the table is too "
                + "large (~" + (rows < 0 ? "unknown" : rows) + " rows) for full comparison. "
                + "Specify a cursor column in the project settings to enable incremental sync.");
    }

    /**
     * Finds a column whose name contains one of the hints.
     *
     * @param requireTemporal when true, only temporal-typed columns qualify; a VARCHAR named
     *                        "update_time" is not safely comparable
     */
    private Optional<ColumnMeta> findByHints(TableMeta table, java.util.List<String> hints,
                                             boolean requireTemporal) {
        // Iterate hints outermost so the most specific convention wins regardless of column
        // order in the table.
        for (String hint : hints) {
            for (ColumnMeta c : table.getColumns()) {
                String upper = c.getName().toUpperCase().replace("-", "_");
                if (!upper.contains(hint)) {
                    continue;
                }
                if (requireTemporal && !CursorStrategy.isTemporalType(c.getJdbcType())) {
                    log.debug("Column {}.{} matches hint {} but is not a temporal type",
                            table.getName(), c.getName(), hint);
                    continue;
                }
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
