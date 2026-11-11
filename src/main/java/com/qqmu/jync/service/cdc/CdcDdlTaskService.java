package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.DdlExecutor;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.TargetTableVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * The DDL tasks a change-log row carries: a skipped CDC DDL opens one, an administrator
 * runs the statement on the target and then replays the rows that were not replicated
 * while the target was behind.
 *
 * <p>Separate from {@code ChangeLogService}, which stays the audit trail: a change-log
 * row records what happened, while a task is work that is still open.
 */
@Slf4j
@Service
public class CdcDdlTaskService {

    private final ChangeLogRepository repository;
    private final ProjectRepository projectRepository;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final DataSourceManager dataSourceManager;
    private final CdcPositionStore positionStore;
    private final CdcConsumerManager cdcConsumerManager;
    private final SyncContextFactory contextFactory;
    private final DataSyncService dataSyncService;

    @Autowired
    public CdcDdlTaskService(ChangeLogRepository repository,
                             ProjectRepository projectRepository,
                             DatabaseConfigRepository databaseConfigRepository,
                             DataSourceManager dataSourceManager,
                             CdcPositionStore positionStore,
                             CdcConsumerManager cdcConsumerManager,
                             SyncContextFactory contextFactory,
                             DataSyncService dataSyncService) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.databaseConfigRepository = databaseConfigRepository;
        this.dataSourceManager = dataSourceManager;
        this.positionStore = positionStore;
        this.cdcConsumerManager = cdcConsumerManager;
        this.contextFactory = contextFactory;
        this.dataSyncService = dataSyncService;
    }

    /**
     * Whether the statement leaves a data gap behind.
     *
     * <p>Only a statement that adds, renames or retypes a column can leave rows behind that
     * the target never received: the projection drops that column, so its values never reach
     * the target and have to be replayed. A statement that only drops things — or only
     * touches table-level attributes — leaves no such gap: there is nothing to bring over,
     * so the task closes as soon as the statement ran.
     */
    static boolean hasColumnGap(String sql) {
        for (String clause : alterClauses(sql)) {
            String head = clause.trim().toUpperCase(Locale.ROOT);
            if (head.startsWith("ADD ") || head.startsWith("MODIFY ")
                    || head.startsWith("CHANGE ") || head.startsWith("RENAME ")
                    || head.startsWith("ALTER ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The columns a plain {@code DROP COLUMN} statement removes.
     *
     * <p>Empty when the statement is not a plain drop — the caller then treats a "column does
     * not exist" error as a real failure instead of as "the target is already aligned".
     */
    static List<String> droppedColumns(String sql) {
        List<String> dropped = new ArrayList<>();
        for (String clause : alterClauses(sql)) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?is)^DROP\\s+(?:COLUMN\\s+)?(.+)$").matcher(clause.trim());
            if (!matcher.matches()) {
                return List.of();
            }
            String name = matcher.group(1).trim();
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.startsWith("INDEX ") || upper.startsWith("KEY ")
                    || upper.startsWith("PRIMARY ") || upper.startsWith("FOREIGN ")
                    || upper.startsWith("CONSTRAINT ") || upper.startsWith("CHECK ")) {
                return List.of();
            }
            dropped.add(firstToken(name));
        }
        return dropped;
    }

    /**
     * The columns a statement adds — the ones whose values the target never received.
     *
     * <p>Only plain columns count: an added index or constraint does not carry data, so it
     * leaves no gap to replay.
     */
    static List<String> addedColumns(String sql) {
        List<String> added = new ArrayList<>();
        for (String clause : alterClauses(sql)) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?is)^ADD\\s+(?:COLUMN\\s+)?(.+)$").matcher(clause.trim());
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.group(1).trim();
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.startsWith("INDEX ") || upper.startsWith("KEY ")
                    || upper.startsWith("UNIQUE ") || upper.startsWith("PRIMARY ")
                    || upper.startsWith("CONSTRAINT ") || upper.startsWith("FOREIGN ")
                    || upper.startsWith("CHECK ")) {
                continue;
            }
            added.add(firstToken(name));
        }
        return added;
    }

    /** The first word of a clause fragment, without its identifier quotes. */
    private static String firstToken(String text) {
        String trimmed = text.trim();
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            trimmed = trimmed.substring(0, space);
        }
        return unquoteIdentifier(trimmed);
    }

    /** The statement behind a change-log row's "CDC_SKIPPED_DDL:" prefix. */
    private static String stripPrefix(String details) {
        if (details == null) {
            return "";
        }
        String prefix = SyncStateWriter.SKIPPED_DDL_PREFIX;
        return details.startsWith(prefix) ? details.substring(prefix.length()) : details;
    }

    /**
     * A statement supersedes the open tasks that touch the same columns: once the target has
     * been aligned for a column, a task asking for an older change to that column is moot.
     *
     * <p>An ADD followed by a DROP of the same column leaves the target needing neither — and
     * without this, the second ADD would be swallowed by the first one's still-open task and
     * never raise an alert again.
     */
    private void supersedeTasksFor(ChangeLog entry, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        for (ChangeLog open : repository.findOpenDdlTasks(entry.getProjectId())) {
            if (open.getId() != null && open.getId().equals(entry.getId())) {
                continue; // the task being executed must not close itself
            }
            List<String> added = addedColumns(stripPrefix(open.getDetails()));
            for (String column : columns) {
                if (!added.isEmpty() && containsIgnoreCase(added, column)) {
                    open.setReplayDone(true);
                    open.setResolutionStatus(ChangeLog.RESOLUTION_PROCESSED);
                    open.setHandledBy(currentActor());
                    open.setHandledAt(Instant.now());
                    repository.save(open);
                    break;
                }
            }
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String wanted) {
        for (String value : values) {
            if (value.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** One identifier, optionally schema-qualified: t, `db`.`t`, db.t, [db].[t]. */
    private static final java.util.regex.Pattern TABLE_IDENTIFIER = java.util.regex.Pattern.compile(
            "^(?:`[^`]+`|\"[^\"]+\"|\\[[^\\]]+\\]|[A-Za-z0-9_$]+)"
                    + "(?:\\s*\\.\\s*(?:`[^`]+`|\"[^\"]+\"|\\[[^\\]]+\\]|[A-Za-z0-9_$]+))?");

    /** The ALTER TABLE clauses, split on the commas that separate them. */
    static List<String> alterClauses(String sql) {
        String statement = sql == null ? "" : sql.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?is)^ALTER\\s+TABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?(.+)$")
                .matcher(statement);
        if (!matcher.matches()) {
            return List.of();
        }
        String rest = matcher.group(1).trim();
        // The table identifier is not a clause. Without stripping it, the first clause still
        // carries the table name and an "ADD COLUMN" at its head is never recognised — a
        // single-clause ADD would be mistaken for a harmless DROP and the task would close
        // right after the DDL, leaving the new column's values unreplicated.
        java.util.regex.Matcher identifier = TABLE_IDENTIFIER.matcher(rest);
        if (identifier.find()) {
            rest = rest.substring(identifier.end()).trim();
        }
        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inBacktick = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (inBacktick) {
                inBacktick = c != '`';
                current.append(c);
                continue;
            }
            if (c == '`') {
                inBacktick = true;
                current.append(c);
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ',') {
                clauses.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            clauses.add(current.toString().trim());
        }
        return clauses;
    }

    private static String unquoteIdentifier(String name) {
        String trimmed = name.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    /**
     * MySQL's "Can't DROP 'x'; check that column/key exists" (1091): the object is not on
     * the target, so a plain drop has nothing left to remove — the target is already in the
     * state the statement asks for.
     */
    private static boolean isMissingObject(SQLException failure) {
        for (SQLException current = failure; current != null;
                current = current.getNextException()) {
            if (current.getErrorCode() == 1091) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the page needs right after the administrator ran the DDL: whether the task
     * still has rows to replay, and which tables/columns those rows belong to.
     */
    public record DdlTaskOutcome(boolean needsReplay, String affectedTables) { }

    private static boolean needsReplay(ChangeLog entry) {
        return Boolean.TRUE.equals(entry.getDdlExecuted())
                && !Boolean.TRUE.equals(entry.getReplayDone())
                && !ChangeLog.RESOLUTION_PROCESSED.equals(entry.getResolutionStatus());
    }

    /**
     * Executes an administrator-edited DDL against the owning project's target database.
     *
     * <p>Deliberately not {@code @Transactional}: the target DDL (implicitly committed by
     * the database) and the H2 metadata row are different resources. Executing first and
     * recording afterwards keeps a failed attempt visible as FAILED instead of rolling the
     * status away with the exception.
     *
     * <p>The connection is always resolved server-side from the log's project — no client
     * supplies a database id, URL or credential — so even an arbitrary edited statement is
     * confined to that project's target database, never the metadata store.
     */
    public DdlTaskOutcome executeDdl(Long logId, String sql) {
        ChangeLog entry = repository.findById(logId)
                .orElseThrow(() -> new IllegalStateException("error.ddl.notFound"));
        if (entry.getDetails() == null
                || !entry.getDetails().startsWith(SyncStateWriter.SKIPPED_DDL_PREFIX)) {
            throw new IllegalStateException("error.ddl.notPending");
        }
        if (ChangeLog.RESOLUTION_PROCESSED.equals(entry.getResolutionStatus())) {
            throw new IllegalStateException("error.ddl.notPending");
        }
        if (Boolean.TRUE.equals(entry.getDdlExecuted())) {
            // already applied; a double submit is harmless
            return new DdlTaskOutcome(needsReplay(entry), entry.getAffectedTables());
        }
        String statement = sql == null ? "" : sql.trim();
        if (statement.isEmpty() || statement.length() > 20_000) {
            throw new IllegalStateException("error.ddl.invalid");
        }
        assertStructureUnchanged(entry, statement);
        Project project = projectRepository.findById(entry.getProjectId())
                .orElseThrow(() -> new IllegalStateException("error.ddl.noTarget"));
        if (project.getTargetDbId() == null) {
            throw new IllegalStateException("error.ddl.noTarget");
        }
        DatabaseConfig target = databaseConfigRepository.findById(project.getTargetDbId())
                .orElseThrow(() -> new IllegalStateException("error.ddl.noTarget"));
        // The schema qualifier names the source database; the statement may only run against
        // the target, so it is rewritten here rather than trusted from the form.
        statement = targetize(statement, target.getDatabaseName());

        // A statement that only drops things leaves no data behind; one that adds, renames
        // or retypes a column leaves that column's values unreplicated.
        boolean gap = hasColumnGap(statement);
        List<String> dropped = droppedColumns(statement);
        try (Connection connection = dataSourceManager.getConnection(target)) {
            // Idempotent: "Duplicate column name" (MySQL 1060) means the target is already
            // aligned, which is the desired end state after a retry or a second admin click.
            new DdlExecutor(connection).executeIdempotentCreate(statement);
        } catch (SQLException | RuntimeException e) {
            if (!dropped.isEmpty() && e instanceof SQLException
                    && isMissingObject((SQLException) e)) {
                // The column is not on the target — it never received the ADD this project
                // skipped — so the DROP has nothing to remove. That is exactly the state
                // the statement asks for.
                log.info("Target {} does not have {} — the DROP has nothing to remove",
                        target.getName(), dropped);
            } else {
                entry.setTargetDdlSql(statement);
                entry.setErrorDetail(brief(e, 2000));
                entry.setResolutionStatus(ChangeLog.RESOLUTION_FAILED);
                entry.setHandledBy(currentActor());
                entry.setHandledAt(Instant.now());
                repository.save(entry);
                throw new IllegalStateException("error.ddl.execute:" + brief(e, 300), e);
            }
        }

        entry.setTargetDdlSql(statement);
        entry.setDdlExecuted(true);
        entry.setErrorDetail(null);
        entry.setHandledBy(currentActor());
        entry.setHandledAt(Instant.now());
        if (gap) {
            // The replay is still outstanding: stay PENDING so retention keeps the task.
            entry.setResolutionStatus(ChangeLog.RESOLUTION_PENDING);
            recordGapBounds(entry);
        } else {
            // Nothing was left behind, so there is no row to bring over: the task is done.
            entry.setReplayDone(true);
            entry.setAffectedTables(null);
            entry.setResolutionStatus(ChangeLog.RESOLUTION_PROCESSED);
        }
        repository.save(entry);
        // This statement supersedes the open tasks that touch the same columns: once the
        // target has been aligned for a column, an older task asking for a change to that
        // column is moot — and the replay covers the whole table anyway.
        List<String> touched = new ArrayList<>(addedColumns(statement));
        touched.addAll(droppedColumns(statement));
        supersedeTasksFor(entry, touched);
        // handledBy / handledAt / targetDdlSql on this row ARE the audit trail. No
        // AdminAuditService row: audit_event.action is a fixed H2 ENUM column and adding a
        // new AuditAction value cannot be applied by ddl-auto=update (H2 22030 on insert).
        return new DdlTaskOutcome(needsReplay(entry), entry.getAffectedTables());
    }

    /**
     * Closes the replication gap of a DDL task: the position the stream has reached when the
     * target was aligned is the end of the window, and the columns the projection had to
     * leave out meanwhile are listed so a replay can bring that data over.
     */
    private void recordGapBounds(ChangeLog entry) {
        if (positionStore != null) {
            CdcPosition at = positionStore.find(entry.getProjectId()).orElse(null);
            if (at != null) {
                entry.setGapEndFile(at.getBinlogFile());
                entry.setGapEndPos(at.getBinlogPos());
            }
        }
        String gaps = describeGaps(runningGaps(entry.getProjectId()));
        if (gaps == null) {
            // The running counters live only as long as the consumer process. A restart
            // between the DDL and the administrator's action would leave the task with
            // nothing to replay, so fall back to comparing the columns directly.
            gaps = diffMissingColumns(entry.getProjectId());
        }
        entry.setAffectedTables(gaps);
    }

    /** The gaps the running consumer recorded, if it is still the one that saw the DDL. */
    private Map<String, Set<String>> runningGaps(Long projectId) {
        if (cdcConsumerManager == null) {
            return null;
        }
        CdcRuntimeStats stats = cdcConsumerManager.statsFor(projectId);
        return stats == null ? null : stats.prunedGaps();
    }

    /**
     * Fallback for the gap list: compare the source and target columns directly and name the
     * tables whose target is still missing a column. This does not depend on the consumer
     * still running, so the task always ends up with something replayable.
     */
    private String diffMissingColumns(Long projectId) {
        if (projectId == null || contextFactory == null || projectRepository == null) {
            return null;
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return null;
        }
        SyncContext ctx = contextFactory.build(project, "ddl-align", () -> true, () -> false);
        if (ctx == null) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        try (Connection sourceConn = dataSourceManager.getConnection(ctx.getSourceConfig());
             Connection targetConn = dataSourceManager.getConnection(ctx.getTargetConfig())) {
            DatabaseMeta meta = ctx.getSourceReader().readAllForData(sourceConn,
                    ctx.getSourceSchema(), null, null, null, false, false);
            if (meta == null || meta.getTables() == null) {
                return null;
            }
            for (TableMeta sourceTable : meta.getTables()) {
                String targetTable = ctx.getConfig().targetTableName(sourceTable.getName());
                Map<String, String> targetColumns = TargetTableVerifier.columnNamesByUpperName(
                        targetConn, ctx.getTargetSchema(), targetTable, ctx.getTargetType());
                if (targetColumns.isEmpty()) {
                    continue;
                }
                List<String> gap = new ArrayList<>();
                for (ColumnMeta column : sourceTable.getColumns()) {
                    if (!targetColumns.containsKey(column.getName().toUpperCase(Locale.ROOT))) {
                        gap.add(column.getName());
                    }
                }
                if (!gap.isEmpty()) {
                    missing.add(sourceTable.getName() + "(" + String.join("+", gap) + ")");
                }
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not compare the source and target columns of project {}: {}",
                    projectId, e.toString());
            return null;
        }
        return missing.isEmpty() ? null : String.join(", ", missing);
    }

    /** Renders the omitted columns as {@code table(col+col), table(col)}. */
    private static String describeGaps(Map<String, Set<String>> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        gaps.forEach((table, columns) -> {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(table).append('(').append(String.join("+", columns)).append(')');
        });
        return text.toString();
    }

    /**
     * Replays the rows that were not replicated while a skipped DDL left the target behind.
     *
     * <p>The affected tables are re-read from the source and upserted over what the target
     * already holds. The load deliberately runs on the "reload over the existing rows" path:
     * the target is never truncated, because it still carries every row the CDC stream
     * delivered before the gap. The project's own table mapping, column rules and type
     * conversion apply, so the replayed rows land exactly like the streamed ones would.
     */
    public void replayDdl(Long logId) {
        ChangeLog entry = repository.findById(logId)
                .orElseThrow(() -> new IllegalStateException("error.ddl.notFound"));
        if (entry.getDetails() == null
                || !entry.getDetails().startsWith(SyncStateWriter.SKIPPED_DDL_PREFIX)) {
            throw new IllegalStateException("error.ddl.notPending");
        }
        if (!Boolean.TRUE.equals(entry.getDdlExecuted())) {
            throw new IllegalStateException("error.ddl.targetNotAligned");
        }
        if (Boolean.TRUE.equals(entry.getReplayDone())) {
            return;
        }
        List<String> tables = parseAffectedTables(entry.getAffectedTables());
        if (tables.isEmpty()) {
            // Marking the task "processed" here would be a false closure: the operator
            // would believe the gap was closed while no row was touched. Say so instead.
            entry.setErrorDetail("no replayable table was recorded for this DDL task");
            entry.setResolutionStatus(ChangeLog.RESOLUTION_FAILED);
            entry.setHandledBy(currentActor());
            entry.setHandledAt(Instant.now());
            repository.save(entry);
            throw new IllegalStateException("error.ddl.nothingToReplay");
        }
        Project project = projectRepository.findById(entry.getProjectId())
                .orElseThrow(() -> new IllegalStateException("error.ddl.noTarget"));
        SyncContext ctx = contextFactory.build(project, "ddl-replay", () -> true, () -> false);
        try (Connection sourceConn = dataSourceManager.getConnection(ctx.getSourceConfig());
             Connection targetConn = dataSourceManager.getConnection(ctx.getTargetConfig())) {
            for (String sourceTable : tables) {
                TableMeta table = ctx.getSourceReader()
                        .readTable(sourceConn, ctx.getSourceSchema(), sourceTable);
                if (table == null || table.getColumns().isEmpty()) {
                    throw new IllegalStateException("error.ddl.tableMissing:" + sourceTable);
                }
                // A progress row that claims the table is loaded keeps the load on the
                // "reload over the existing rows" path — the target is never truncated.
                SyncProgress progress = new SyncProgress();
                progress.setInitialLoadDone(true);
                progress.setLastSyncValue("ddl-gap-replay");
                DataSyncService.TableSyncResult result = dataSyncService.syncTable(sourceConn,
                        targetConn, table, CursorStrategy.fullCompare("DDL gap replay"),
                        progress, ctx);
                if (result.getError() != null) {
                    throw new IllegalStateException("error.ddl.replay:" + result.getError());
                }
            }
        } catch (RuntimeException | SQLException e) {
            entry.setErrorDetail(brief(e, 2000));
            entry.setResolutionStatus(ChangeLog.RESOLUTION_FAILED);
            entry.setHandledBy(currentActor());
            entry.setHandledAt(Instant.now());
            repository.save(entry);
            throw new IllegalStateException("error.ddl.replay:" + brief(e, 300), e);
        }
        markReplayed(entry);
    }

    private void markReplayed(ChangeLog entry) {
        entry.setReplayDone(true);
        entry.setErrorDetail(null);
        entry.setResolutionStatus(ChangeLog.RESOLUTION_PROCESSED);
        entry.setHandledBy(currentActor());
        entry.setHandledAt(Instant.now());
        repository.save(entry);
    }

    /** Reads "table(col+col), table(col)" back into the source table names. */
    private static List<String> parseAffectedTables(String affected) {
        List<String> tables = new ArrayList<>();
        if (affected == null || affected.isBlank()) {
            return tables;
        }
        for (String entry : affected.split(",")) {
            String name = entry.trim();
            int bracket = name.indexOf('(');
            if (bracket > 0) {
                name = name.substring(0, bracket);
            }
            if (!name.isBlank()) {
                tables.add(name.trim());
            }
        }
        return tables;
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? "system" : auth.getName();
    }

    /**
     * The target database name of a project. A binlog DDL always qualifies the table with the
     * <em>source</em> database, which is rarely the target's, so the statement has to be
     * rewritten before it can run on the target.
     */
    public String targetDatabaseName(Long projectId) {
        if (projectId == null || projectRepository == null || databaseConfigRepository == null) {
            return null;
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getTargetDbId() == null) {
            return null;
        }
        DatabaseConfig target = databaseConfigRepository.findById(project.getTargetDbId())
                .orElse(null);
        return target == null ? null : target.getDatabaseName();
    }

    /**
     * Rewrites the schema qualifier of an ALTER TABLE to the target database.
     *
     * <p>The qualifier is not the administrator's to change: the statement may only run
     * against the target, and the name in the form came from the source's binlog. Whatever
     * was submitted is therefore replaced here instead of being trusted. A blank target name
     * drops the qualifier so the statement applies to the connection's default schema.
     */
    static String targetize(String sql, String targetDatabase) {
        if (sql == null) {
            return "";
        }
        String name = targetDatabase == null ? "" : targetDatabase.trim();
        String qualifier = name.isEmpty()
                ? "" : java.util.regex.Matcher.quoteReplacement("`" + name + "`.");
        return sql.replaceAll(
                "(?is)^(\\s*ALTER\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?)"
                        + "(`[^`]+`|\"[^\"]+\"|[A-Za-z0-9_$]+)\\s*\\.\\s*",
                qualifier.isEmpty() ? "$1" : "$1" + qualifier);
    }

    /**
     * An administrator may fix a COMMENT (binlog text can be mojibake) and adapt dialect
     * details (identifier quoting, schema qualification, case, trailing semicolon). The
     * operation, table, columns, types and clause order must stay identical to the DDL the
     * CDC stream actually saw, or the target could silently diverge from the source layout.
     */
    private static void assertStructureUnchanged(ChangeLog entry, String edited) {
        String original = entry.getDetails()
                .substring(SyncStateWriter.SKIPPED_DDL_PREFIX.length());
        if (!normalizeDdl(original).equals(normalizeDdl(edited))) {
            throw new IllegalStateException("error.ddl.statementChanged");
        }
    }

    static String normalizeDdl(String sql) {
        String s = sql == null ? "" : sql.trim();
        s = s.replaceAll("(?is)\\bCOMMENT\\s+'(?:[^']|'')*'", " ");
        s = s.replaceAll("(?is)\\bCOMMENT\\s+\"(?:[^\"]|\"\")*\"", " ");
        // `db`.`table` / db.table -> table: schema qualification is a dialect detail.
        s = s.replaceAll("(?i)(`[^`]+`|\"[^\"]+\"|[A-Za-z0-9_$]+)\\s*\\.\\s*"
                + "(`[^`]+`|\"[^\"]+\"|[A-Za-z0-9_$]+)", "$2");
        s = s.replaceAll("[`\"\\[\\]]", "");
        s = s.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String brief(Throwable failure, int limit) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > limit ? message.substring(0, limit) + "..." : message;
    }
}
