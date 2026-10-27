package com.qqmu.jync.service.bulk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.bulk.BulkPlan;
import com.qqmu.jync.dto.bulk.BulkPlanRequest;
import com.qqmu.jync.dto.bulk.StatsSource;
import com.qqmu.jync.dto.bulk.TableEntry;
import com.qqmu.jync.dto.bulk.TaskPlan;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.CaptureStrategyParser;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.util.JdbcUtil;

import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Plans and creates a set of sync tasks from one source's table listing.
 *
 * <p>Reads the listing once through the source connection's <em>transient</em> pool (the same
 * two-connection, uncached pool "Test connection" uses), so a planning pass never touches the
 * pool a running sync is using. Sizes come from the product's catalog; {@code COUNT(*)} runs
 * only when the user asks for exact numbers, and only for the tables the catalog could not
 * size, one statement at a time with a per-statement timeout and a whole-page budget.
 *
 * <p>Nothing here feeds the sync engine: the plan is a value the page renders, and
 * {@link #create} persists projects through {@link ProjectService}'s existing save paths so
 * every validation, serialization and audit trail is the one already in place.
 */
@Component
@Slf4j
public class BulkPlanService {

    /** Upper bound for the number of generated tasks; the page says so when it clamps. */
    static final int MAX_TASK_COUNT = 50;

    private static final int MAX_PREFIX_LENGTH = 100;
    private static final long DEFAULT_EXACT_COUNT_BUDGET_MS = 120_000L;
    private static final int EXACT_COUNT_TIMEOUT_SECONDS = 15;

    private final DatabaseConfigService databaseConfigService;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory metadataReaderFactory;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final long exactCountBudgetMs;
    private final TransactionTemplate transactions;

    @Autowired
    public BulkPlanService(DatabaseConfigService databaseConfigService,
                           DataSourceManager dataSourceManager,
                           MetadataReaderFactory metadataReaderFactory,
                           ProjectRepository projectRepository,
                           ProjectService projectService,
                           PlatformTransactionManager transactionManager) {
        this(databaseConfigService, dataSourceManager, metadataReaderFactory,
                projectRepository, projectService, DEFAULT_EXACT_COUNT_BUDGET_MS,
                transactionManager);
    }

    /** Visible for tests that need a deterministic budget. */
    BulkPlanService(DatabaseConfigService databaseConfigService,
                    DataSourceManager dataSourceManager,
                    MetadataReaderFactory metadataReaderFactory,
                    ProjectRepository projectRepository,
                    ProjectService projectService,
                    long exactCountBudgetMs,
                    PlatformTransactionManager transactionManager) {
        this.databaseConfigService = databaseConfigService;
        this.dataSourceManager = dataSourceManager;
        this.metadataReaderFactory = metadataReaderFactory;
        this.projectRepository = projectRepository;
        this.projectService = projectService;
        this.exactCountBudgetMs = exactCountBudgetMs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Reads the source and bins its tables into at most {@code taskCount} groups.
     *
     * @throws IllegalArgumentException with a message key the page knows how to translate
     */
    public BulkPlan plan(BulkPlanRequest request) {
        if (request == null || request.sourceDbId() == null || request.targetDbId() == null) {
            throw new IllegalArgumentException("error.project.bulk.source.not.found");
        }
        if (request.sourceDbId().equals(request.targetDbId())) {
            throw new IllegalArgumentException("error.project.same.database");
        }
        if (request.taskCount() < 1) {
            // The form's min=1 does not guard a direct POST; silently turning 0 into one
            // task would create a plan the user never asked for.
            throw new IllegalArgumentException("error.project.bulk.taskcount.invalid");
        }
        String prefix = normalizePrefix(request.namePrefix());
        DatabaseConfig source = databaseConfigService.findById(request.sourceDbId())
                .orElseThrow(() -> new IllegalArgumentException("error.project.bulk.source.not.found"));
        DatabaseConfig target = databaseConfigService.findById(request.targetDbId())
                .orElseThrow(() -> new IllegalArgumentException("error.project.bulk.source.not.found"));
        // The page's dropdowns only list the matching role, but a direct POST can name any
        // connection. Reading a target as the source would scan the wrong database for a
        // listing and then quietly sync from it. A null role is a legacy row: leave it alone.
        if (source.getRole() != null && source.getRole() != ConnectionRole.SOURCE) {
            throw new IllegalArgumentException("error.project.bulk.source.role");
        }
        if (target.getRole() != null && target.getRole() != ConnectionRole.TARGET) {
            throw new IllegalArgumentException("error.project.bulk.target.role");
        }
        // Refuse a strategy the source cannot serve BEFORE the listing is read: the preview
        // must not present a plan that create() would then reject in saveConfig, and a bogus
        // strategy name has no reason to cost a source connect at all.
        CaptureStrategyParser.parseRequested(request.captureStrategy(), source.getType());
        MetadataReader reader = metadataReaderFactory.forType(source.getType(),
                source.resolvedFamily());

        HikariDataSource dataSource;
        try {
            dataSource = dataSourceManager.createTransientDataSource(source);
        } catch (RuntimeException e) {
            log.warn("Could not open a connection to '{}' for the bulk plan: {}",
                    source.getName(), e.getMessage());
            throw new IllegalArgumentException("error.project.bulk.source.failed");
        }
        try (dataSource; Connection conn = dataSource.getConnection()) {
            return planOn(conn, reader, source, target, request, prefix);
        } catch (SQLException e) {
            log.warn("Could not read the table listing of '{}': {}", source.getName(),
                    e.getMessage());
            throw new IllegalArgumentException("error.project.bulk.source.failed");
        }
    }

    private BulkPlan planOn(Connection conn, MetadataReader reader, DatabaseConfig source,
                            DatabaseConfig target, BulkPlanRequest request, String prefix)
            throws SQLException {
        String schema = normalizeSchema(source, source.getSchemaName());
        if (schema == null || schema.isBlank()) {
            schema = reader.resolveDefaultSchema(conn);
        }
        List<String> tableNames = reader.listTableNames(conn, schema);
        if (tableNames == null || tableNames.isEmpty()) {
            throw new IllegalArgumentException("error.project.bulk.no.tables");
        }
        Map<String, MetadataReader.TableStat> stats = reader.estimateTableStats(conn, schema);
        if (stats == null) {
            stats = Map.of();
        }

        Map<String, TableEntry> entries = new LinkedHashMap<>();
        boolean catalogStatsUsed = false;
        for (String name : tableNames) {
            if (name == null || name.isBlank() || entries.containsKey(name)) {
                continue;
            }
            MetadataReader.TableStat stat = stats.get(name);
            Long rows = stat == null ? null : stat.rows();
            Long bytes = stat == null ? null : stat.bytes();
            if (rows != null || bytes != null) {
                // Any catalog number counts: MySQL reports bytes for a table whose row
                // count is still uncollected, and the page must not then claim the library
                // gave no statistics at all while its table shows a size.
                catalogStatsUsed = true;
            }
            entries.put(name, new TableEntry(name, rows, bytes,
                    rows == null && bytes == null
                            ? StatsSource.NONE : StatsSource.CATALOG));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("error.project.bulk.no.tables");
        }

        boolean exactStatsUsed = false;
        boolean exactStatsTruncated = false;
        if (request.exactStats()) {
            List<String> unknown = entries.values().stream()
                    .filter(entry -> entry.rows() == null)
                    .map(TableEntry::name)
                    .toList();
            if (!unknown.isEmpty()) {
                ExactCounts counts = exactCounts(conn, schema, unknown);
                exactStatsTruncated = counts.truncated();
                exactStatsUsed = !counts.counts().isEmpty();
                counts.counts().forEach((name, rows) -> entries.put(name, new TableEntry(
                        name, rows, entries.get(name).bytes(), StatsSource.EXACT)));
            }
        }

        List<BinPacker.Item> items = entries.values().stream()
                .map(entry -> new BinPacker.Item(entry.name(), entry.rows()))
                .toList();
        int requested = request.taskCount();
        List<BinPacker.Bin> bins = BinPacker.pack(items, Math.min(requested, MAX_TASK_COUNT));
        boolean taskCountClamped = bins.size() < requested;

        int width = Math.max(2, String.valueOf(bins.size()).length());
        List<TaskPlan> tasks = new ArrayList<>(bins.size());
        long estimatedRows = 0L;
        int unknownTables = 0;
        for (BinPacker.Bin bin : bins) {
            List<TableEntry> tables = new ArrayList<>(bin.items().size());
            long rowsInBin = 0L;
            for (BinPacker.Item item : bin.items()) {
                TableEntry entry = entries.get(item.name());
                tables.add(entry);
                if (entry.rows() == null) {
                    unknownTables++;
                } else {
                    rowsInBin += entry.rows();
                }
            }
            estimatedRows += rowsInBin;
            String name = prefix + "-" + String.format(Locale.ROOT,
                    "%0" + width + "d", bin.index() + 1);
            tasks.add(new TaskPlan(name, List.copyOf(tables), rowsInBin));
        }

        return new BulkPlan(source.getName(), target.getName(), schema, List.copyOf(tasks),
                entries.size(), estimatedRows, unknownTables, catalogStatsUsed, exactStatsUsed,
                exactStatsTruncated, taskCountClamped);
    }

    /**
     * Creates one stopped task per bin, all in one transaction.
     *
     * <p>The plan is recomputed here rather than carried from the preview: the preview is a
     * GET-rendered view with no state, and re-reading keeps the create path honest about what
     * the source looks like now. Existing names are refused before anything is written, so a
     * name clash can never leave a half-created set behind.
     *
     * @return the number of tasks created
     */
    public int create(BulkPlanRequest request) {
        // The plan is computed BEFORE the transaction opens: reading the listing (and up
        // to two minutes of exact counting) then holds no metadata-database connection,
        // and — just as important — the duplicate-name check stays milliseconds away from
        // the inserts. A read-then-insert window stretched to minutes let two concurrent
        // creates both pass the check and write duplicate names; there is no unique
        // constraint on Project.name, and a later edit of such a pair fails on findByName.
        BulkPlan plan = plan(request);
        return transactions.execute(status -> {
            for (TaskPlan task : plan.tasks()) {
                if (projectRepository.findByName(task.name()).isPresent()) {
                    throw new IllegalArgumentException(
                            "error.project.bulk.name.duplicate:" + task.name());
                }
            }
            for (TaskPlan task : plan.tasks()) {
                Project project = new Project();
                project.setName(task.name());
                project.setSourceDbId(request.sourceDbId());
                project.setTargetDbId(request.targetDbId());
                Project saved = projectService.save(project);

                SyncConfig config = new SyncConfig();
                config.setObjectSelectionExplicit(true);
                config.setTables(new LinkedHashSet<>(task.tables().stream()
                        .map(TableEntry::name).toList()));
                projectService.saveConfig(saved.getId(), config, request.captureStrategy());
            }
            log.info("Bulk create: {} task(s) from '{}'.'{}' to '{}'", plan.tasks().size(),
                    plan.sourceName(), plan.schema(), plan.targetName());
            return plan.tasks().size();
        });
    }

    /**
     * The plan as a spreadsheet: UTF-8 BOM, CRLF, one row per table.
     *
     * <p>Labels are passed in because the page is localized; this class only knows the shape.
     */
    public String toCsv(BulkPlan plan, CsvLabels labels) {
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(csvRow(labels.header())).append("\r\n");
        for (int i = 0; i < plan.tasks().size(); i++) {
            TaskPlan task = plan.tasks().get(i);
            String index = Integer.toString(i + 1);
            for (TableEntry table : task.tables()) {
                csv.append(csvRow(List.of(
                        index,
                        task.name(),
                        table.name(),
                        table.rows() == null ? "" : table.rows().toString(),
                        table.bytes() == null ? "" : table.bytes().toString(),
                        sourceLabel(table.source(), labels)))).append("\r\n");
            }
        }
        return csv.toString();
    }

    /** Column labels and the three stats-source words, resolved from the page's bundle. */
    public record CsvLabels(List<String> header, String catalog, String exact, String unknown) {
    }

    private static String sourceLabel(StatsSource source, CsvLabels labels) {
        return switch (source) {
            case CATALOG -> labels.catalog();
            case EXACT -> labels.exact();
            case NONE -> labels.unknown();
        };
    }

    private static String csvRow(List<String> fields) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(csvField(fields.get(i)));
        }
        return row.toString();
    }

    private static String csvField(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        // Quoting alone does not stop a spreadsheet: Excel, Sheets and WPS still evaluate
        // a cell that starts with = + - @ (or a tab/CR control character) after the file
        // is opened, and a table name is attacker-chosen. A leading apostrophe is the
        // spreadsheet's own "this is text" marker and is not shown in the cell.
        char first = value.charAt(0);
        String safe = first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r'
                ? "'" + value
                : value;
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    /**
     * A configured schema in the form the source actually stores it.
     *
     * <p>Oracle-family and DB2 dictionaries key unquoted identifiers upper case, and their
     * readers fold a configured name the same way (the sync path does too:
     * {@code SyncContextFactory.normalizeConfiguredSchema}). The exact-count statement below
     * quotes this name verbatim, so a lower-case "scott" would address a schema that does not
     * exist and every count would silently fail back to "unknown" — the listing itself hides
     * the problem because the reader folds internally.
     */
    private static String normalizeSchema(DatabaseConfig source, String schema) {
        if (schema == null) {
            return null;
        }
        String trimmed = schema.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        DatabaseType.DialectFamily family = source.resolvedFamily();
        return family == DatabaseType.DialectFamily.ORACLE
                || family == DatabaseType.DialectFamily.DB2
                ? trimmed.toUpperCase(Locale.ROOT)
                : trimmed;
    }

    private static String normalizePrefix(String raw) {
        String prefix = raw == null ? "" : raw.trim();
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("error.project.bulk.prefix.required");
        }
        if (prefix.length() > MAX_PREFIX_LENGTH) {
            throw new IllegalArgumentException("error.project.bulk.prefix.too.long");
        }
        return prefix;
    }

    /**
     * Query timeout for one exact count: the fixed per-statement cap, clamped to what the
     * page budget has left (rounded up to the next second). Zero means the budget is spent.
     *
     * <p>Without the clamp the last statement could still run its full 15 seconds after the
     * 120-second budget ran out, turning the page's promise into 135.
     */
    static int statementTimeoutSeconds(long remainingMs) {
        if (remainingMs <= 0) {
            return 0;
        }
        long seconds = (remainingMs + 999) / 1000;
        return (int) Math.min(EXACT_COUNT_TIMEOUT_SECONDS, Math.max(1L, seconds));
    }

    private ExactCounts exactCounts(Connection conn, String schema, List<String> tables) {
        Map<String, Long> counts = new HashMap<>();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, exactCountBudgetMs));
        long deadline = System.nanoTime() + budgetNanos;
        boolean truncated = false;
        String quote = JdbcUtil.identifierQuote(conn);
        for (String table : tables) {
            int timeoutSeconds = statementTimeoutSeconds(
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            if (timeoutSeconds == 0) {
                truncated = true;
                break;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + JdbcUtil.qualifiedName(quote, schema, table))) {
                JdbcUtil.applyQueryTimeout(ps, timeoutSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        counts.put(table, Math.max(0L, rs.getLong(1)));
                    }
                }
            } catch (SQLException e) {
                // One table without a count is a display detail; the remaining budget is
                // better spent on its neighbours than on a retry.
                log.debug("Exact row count failed for {}: {}", table, e.getMessage());
            }
        }
        return new ExactCounts(counts, truncated);
    }

    private record ExactCounts(Map<String, Long> counts, boolean truncated) {
    }
}
