package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
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
     * <p>Only a clause that adds, renames or retypes a column can leave rows behind that
     * the target never received: the projection drops that column, so its values never reach
     * the target and have to be replayed. A statement that only drops things — or only
     * touches table-level objects and attributes (indexes, constraints, partitions, defaults)
     * — leaves no such gap: there is nothing to bring over, so the task closes as soon as
     * the statement ran.
     */
    static boolean hasColumnGap(String sql) {
        return hasColumnGap(sql, true);
    }

    static boolean hasColumnGap(String sql, boolean backslashEscapes) {
        for (String clause : alterClauses(sql, backslashEscapes)) {
            if (leavesDataGap(clause)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code ADD} sub-clauses that add a table-level object rather than a column. */
    private static final List<String> NON_DATA_ADDITIONS = List.of(
            "INDEX ", "KEY ", "UNIQUE ", "PRIMARY ", "FULLTEXT ", "SPATIAL ",
            "CONSTRAINT ", "FOREIGN ", "CHECK ", "PARTITION ");

    /**
     * Whether one ALTER clause changes the data the projection carries.
     *
     * <p>{@code ADD INDEX}, {@code ADD CONSTRAINT} and {@code ADD PARTITION} add no column
     * value; a default is applied by the database itself, so every value already reached the
     * target. Treating any of them as a gap parked the task in PENDING forever: the replay
     * refuses a task with no recorded table, and after the target was aligned the live column
     * diff finds nothing to name.
     */
    private static boolean leavesDataGap(String clause) {
        String head = clause.trim().toUpperCase(Locale.ROOT);
        if (head.startsWith("ADD ")) {
            String addition = head.substring("ADD ".length()).trim();
            for (String object : NON_DATA_ADDITIONS) {
                if (addition.startsWith(object)) {
                    return false;
                }
            }
            return true;
        }
        if (head.startsWith("MODIFY ") || head.startsWith("CHANGE ")) {
            return true;
        }
        if (head.startsWith("RENAME ")) {
            String target = head.substring("RENAME ".length()).trim();
            // RENAME TO / AS renames the table itself: every value already on the target
            // moved with it, and replaying the old name would find no table. RENAME INDEX
            // and RENAME KEY move a table-level object, which carries no data either.
            // RENAME COLUMN leaves the gap window's rows behind, so it stays a gap.
            return !target.startsWith("TO ") && !target.startsWith("AS ")
                    && !target.startsWith("INDEX ") && !target.startsWith("KEY ");
        }
        if (head.startsWith("ALTER ")) {
            String rest = head.substring("ALTER ".length()).trim();
            if (rest.startsWith("INDEX ") || rest.startsWith("CONSTRAINT ")
                    || rest.startsWith("CHECK ") || rest.startsWith("PARTITION ")) {
                return false;
            }
            if (rest.startsWith("COLUMN ")) {
                rest = rest.substring("COLUMN ".length()).trim();
            }
            // ALTER COLUMN c SET DEFAULT: the column name comes first, so the action follows
            // the first token.
            int space = rest.indexOf(' ');
            String action = space < 0 ? "" : rest.substring(space + 1).trim();
            return !action.startsWith("SET DEFAULT") && !action.startsWith("DROP DEFAULT");
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
        return droppedColumns(sql, true);
    }

    static List<String> droppedColumns(String sql, boolean backslashEscapes) {
        List<String> dropped = new ArrayList<>();
        for (String clause : alterClauses(sql, backslashEscapes)) {
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
        return addedColumns(sql, true);
    }

    static List<String> addedColumns(String sql, boolean backslashEscapes) {
        List<String> added = new ArrayList<>();
        for (String clause : alterClauses(sql, backslashEscapes)) {
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
    private void supersedeTasksFor(ChangeLog entry, List<String> columns,
                                   boolean backslashEscapes) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        // The source statement names the table the stream saw. The executed row's
        // targetDdlSql is already rewritten to the mapped target name, which would never
        // equal the source name on the open tasks.
        String executedTable = alteredTable(stripPrefix(entry.getDetails()));
        for (ChangeLog open : repository.findOpenDdlTasks(entry.getProjectId())) {
            if (open.getId() != null && open.getId().equals(entry.getId())) {
                continue; // the task being executed must not close itself
            }
            String openTable = alteredTable(stripPrefix(open.getDetails()));
            if (executedTable != null && openTable != null
                    && !executedTable.equalsIgnoreCase(openTable)) {
                // Same column name on a different table is a different gap: aligning one
                // table says nothing about the other.
                continue;
            }
            List<String> added = addedColumns(stripPrefix(open.getDetails()), backslashEscapes);
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

    /** One table identifier, in any of the quoting styles the supported dialects use. */
    private static final String IDENT =
            "(?:`[^`]+`|\"[^\"]+\"|\\[[^\\]]+\\]|[A-Za-z0-9_$]+)";

    /**
     * The statement heads that name a table. Skipped DDL is not always an ALTER: CREATE,
     * DROP, RENAME and TRUNCATE are filed too, and each has to be rewritten before it runs
     * on the target connection.
     */
    private static final String TABLE_HEAD =
            "(?:ALTER|CREATE|DROP)\\s+TABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?"
                    + "|TRUNCATE\\s+(?:TABLE\\s+)?"
                    + "|RENAME\\s+TABLE\\s+";

    /** One identifier, optionally schema-qualified: t, `db`.`t`, db.t, [db].[t]. */
    private static final java.util.regex.Pattern TABLE_IDENTIFIER = java.util.regex.Pattern.compile(
            "^(?:" + IDENT + ")(?:\\s*\\.\\s*(?:" + IDENT + "))?");

    /**
     * The table a DDL statement changes, without its schema qualifier.
     *
     * <p>When neither the running consumer nor the live column diff can name the affected
     * tables, the statement itself still says which table's rows were omitted meanwhile.
     * For {@code RENAME TABLE} this is the old name — the one the sync and its mapping
     * know; a multi-table {@code DROP} names its first table.
     */
    static String alteredTable(String sql) {
        String statement = sql == null ? "" : sql.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?is)^(?:" + TABLE_HEAD + ")(.+)$")
                .matcher(statement);
        if (!matcher.matches()) {
            return null;
        }
        java.util.regex.Matcher identifier = TABLE_IDENTIFIER.matcher(matcher.group(1).trim());
        if (!identifier.find()) {
            return null;
        }
        String name = identifier.group();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        name = unquoteIdentifier(name.trim());
        return name.isBlank() ? null : name;
    }

    /** The ALTER TABLE clauses, split on the commas that separate them. */
    static List<String> alterClauses(String sql) {
        return alterClauses(sql, true);
    }

    /**
     * @param backslashEscapes whether a backslash escapes the next character inside a string
     *                         literal. MySQL does; Oracle, DM, SQL Server and PostgreSQL end
     *                         the literal at the quote, so {@code DEFAULT 'C:\'} really is
     *                         followed by the next clause separator.
     */
    static List<String> alterClauses(String sql, boolean backslashEscapes) {
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
        int depth = 0;
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
                if (backslashEscapes && c == '\\' && i + 1 < rest.length()) {
                    // MySQL keeps backslash escapes inside string literals; the escaped
                    // character must not close the quote and expose its comma as a clause
                    // boundary. Other dialects have no such escape in a quoted literal.
                    current.append(c).append(rest.charAt(++i));
                    continue;
                }
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
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            }
            // Commas inside a type or a value list — DECIMAL(10,2), ENUM('a','b') —
            // separate parts of the same clause, not clauses.
            if (c == ',' && depth == 0) {
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
            if ((first == '`' && last == '`') || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
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
        // A statement that only drops things leaves no data behind; one that adds, renames
        // or retypes a column leaves that column's values unreplicated.
        boolean backslashEscapes = backslashEscapes(project);
        boolean gap = hasColumnGap(statement, backslashEscapes);
        List<String> dropped = droppedColumns(statement, backslashEscapes);
        // The binlog names the source table; the project may map it to a different target
        // name. Without the mapping the DDL lands on a table the sync never writes.
        String sourceTable = alteredTable(stripPrefix(entry.getDetails()));
        SyncConfig targetConfig = contextFactory == null ? null
                : contextFactory.parseConfig(project);
        try (Connection connection = dataSourceManager.getConnection(target)) {
            String identifierQuote = com.qqmu.jync.util.JdbcUtil.identifierQuote(connection);
            // ``target.getDatabaseName()`` is a schema only in MySQL; elsewhere it is a SID
            // or service name and qualifying with it would fail on a healthy target.
            statement = targetize(statement, executionSchema(target, connection),
                    identifierQuote);
            statement = retargetTable(statement, sourceTable,
                    targetConfig == null ? null : targetConfig.targetTableName(sourceTable),
                    identifierQuote);
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
            if (parseAffectedTables(entry.getAffectedTables()).isEmpty()) {
                // No table of this statement is synced by the project, so none of its rows
                // ever reached the target: there is no gap to replay and the task is done.
                entry.setReplayDone(true);
                entry.setAffectedTables(null);
                entry.setResolutionStatus(ChangeLog.RESOLUTION_PROCESSED);
            }
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
        List<String> touched = new ArrayList<>(addedColumns(statement, backslashEscapes));
        touched.addAll(droppedColumns(statement, backslashEscapes));
        supersedeTasksFor(entry, touched, backslashEscapes);
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
        // Only the statement's own table belongs to this task: another table's pruned rows
        // came from a different structural change and must stay available to its own task.
        String ownTable = alteredTable(stripPrefix(entry.getDetails()));
        Map<String, Set<String>> own = ownTable == null
                ? runningGaps(entry.getProjectId())
                : gapsFor(runningGaps(entry.getProjectId()), ownTable);
        String gaps = describeGaps(own);
        if (gaps != null) {
            // The task row now owns the gap; the live map must not hand it to the next
            // task as well.
            CdcRuntimeStats stats = statsFor(entry.getProjectId());
            if (stats != null) {
                stats.clearPrunedGaps(own.keySet());
            }
        }
        if (gaps == null) {
            // The running counters live only as long as the consumer process. A restart
            // between the DDL and the administrator's action would leave the task with
            // nothing to replay, so fall back to comparing the columns directly.
            gaps = diffMissingColumns(entry.getProjectId());
        }
        if (gaps == null) {
            // After the target was aligned the column diff finds nothing, so it cannot name
            // the gap; the statement itself still says which table lost rows meanwhile.
            gaps = statementGapTable(entry);
        }
        entry.setAffectedTables(gaps);
    }

    /**
     * The table named by the statement itself, unless the project does not sync it.
     *
     * <p>A table the project never syncs had no row replicated at all: keeping its task open
     * would leave it PENDING forever, because the replay refuses a task with no table.
     */
    private String statementGapTable(ChangeLog entry) {
        // The source statement, not targetDdlSql: the latter has already been rewritten to
        // the mapped target table, while a replay reads the table from the source.
        String table = alteredTable(stripPrefix(entry.getDetails()));
        if (table == null) {
            return null;
        }
        Project project = projectRepository == null || entry.getProjectId() == null
                ? null : projectRepository.findById(entry.getProjectId()).orElse(null);
        if (project != null && contextFactory != null) {
            SyncConfig config = contextFactory.parseConfig(project);
            // A config that cannot be parsed is not a reason to close the task: err on the
            // side of keeping the replay available.
            if (config != null && !config.includesTable(table)) {
                log.info("DDL task {} changes {}, which project {} does not sync;"
                        + " no row of it was replicated", entry.getId(), table,
                        project.getId());
                return null;
            }
        }
        return table;
    }

    /**
     * The entries of a pruned-gap map that belong to one source table.
     *
     * <p>Keys and the statement's identifier may differ in case (the binlog spells a table
     * the way the source does), so the match is case-insensitive.
     */
    private static Map<String, Set<String>> gapsFor(Map<String, Set<String>> gaps,
                                                    String table) {
        if (gaps == null) {
            return null;
        }
        Map<String, Set<String>> own = new LinkedHashMap<>();
        gaps.forEach((name, columns) -> {
            if (name != null && name.equalsIgnoreCase(table)) {
                own.put(name, columns);
            }
        });
        return own;
    }

    /** The gaps the running consumer recorded, if it is still the one that saw the DDL. */
    private Map<String, Set<String>> runningGaps(Long projectId) {
        CdcRuntimeStats stats = statsFor(projectId);
        return stats == null ? null : stats.prunedGaps();
    }

    /** The live consumer's counters, or null when the project is not streaming. */
    private CdcRuntimeStats statsFor(Long projectId) {
        if (cdcConsumerManager == null) {
            return null;
        }
        return cdcConsumerManager.statsFor(projectId);
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
     * Closes a skipped-DDL task without touching the target.
     *
     * <p>Not every task needs the two-step flow: the administrator may have aligned the
     * table by hand, or decided the affected rows do not matter. Without a way to close it,
     * the task stays open forever — the column projection stays on and retention never
     * prunes the row.
     *
     * <p>Idempotent: a double click (or a second administrator) finds it already processed.
     */
    public void dismissDdl(Long logId) {
        ChangeLog entry = repository.findById(logId)
                .orElseThrow(() -> new IllegalStateException("error.ddl.notFound"));
        if (entry.getDetails() == null
                || !entry.getDetails().startsWith(SyncStateWriter.SKIPPED_DDL_PREFIX)) {
            throw new IllegalStateException("error.ddl.notPending");
        }
        if (ChangeLog.RESOLUTION_PROCESSED.equals(entry.getResolutionStatus())) {
            return;
        }
        entry.setReplayDone(true);
        entry.setResolutionStatus(ChangeLog.RESOLUTION_PROCESSED);
        entry.setErrorDetail(null);
        entry.setHandledBy(currentActor());
        entry.setHandledAt(Instant.now());
        repository.save(entry);
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
     * Rewrites the schema qualifier of a table DDL to the target database.
     *
     * <p>The qualifier is not the administrator's to change: the statement may only run
     * against the target, and the name in the form came from the source's binlog. Whatever
     * was submitted is therefore replaced here instead of being trusted. A blank target name
     * drops the qualifier so the statement applies to the connection's default schema.
     */
    static String targetize(String sql, String targetSchema, String identifierQuote) {
        if (sql == null) {
            return "";
        }
        String name = targetSchema == null ? "" : targetSchema.trim();
        String qualifier = name.isEmpty()
                ? "" : java.util.regex.Matcher.quoteReplacement(
                        com.qqmu.jync.util.JdbcUtil.quotedIdentifier(identifierQuote, name) + ".");
        String rewritten = sql.replaceFirst(
                "(?is)^(\\s*(?:" + TABLE_HEAD + "))" + IDENT + "\\s*\\.\\s*",
                qualifier.isEmpty() ? "$1" : "$1" + qualifier);
        if (listsSeveralTables(rewritten)) {
            // RENAME names its second side after TO; DROP may list several tables. Every
            // qualifier in the list is a schema, and each one has to be the target's.
            rewritten = rewritten.replaceAll(
                    "(?is)" + IDENT + "\\s*\\.\\s*(?=" + IDENT + ")",
                    qualifier);
        }
        return rewritten;
    }

    /** Whether the statement head is followed by more than one table reference. */
    private static boolean listsSeveralTables(String sql) {
        String upper = sql == null ? "" : sql.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("RENAME TABLE") || upper.startsWith("DROP TABLE");
    }

    /**
     * Rewrites the table a DDL statement changes to the project's mapped target name.
     *
     * <p>Only the statement head is touched: the table token after the head keyword and its
     * optional schema qualifier. Columns and clauses stay exactly as submitted. A blank
     * mapping, an unknown source table, or a mapping to the same name leaves the statement
     * unchanged.
     */
    static String retargetTable(String sql, String sourceTable, String targetTable,
                                String identifierQuote) {
        String target = targetTable == null ? "" : targetTable.trim();
        if (sql == null || target.isEmpty() || sourceTable == null
                || target.equalsIgnoreCase(sourceTable.trim())) {
            return sql;
        }
        String quoted = java.util.regex.Matcher.quoteReplacement(
                com.qqmu.jync.util.JdbcUtil.quotedIdentifier(identifierQuote, target));
        return sql.replaceFirst(
                "(?is)^(\\s*(?:" + TABLE_HEAD + "))"
                        + "((?:" + IDENT + "\\s*\\.\\s*)?)"
                        + "(" + IDENT + ")",
                "$1$2" + quoted);
    }

    /**
     * The schema the statement must be applied to: the configured one, the database name for
     * MySQL (where the two coincide), or the connection's current schema.
     */
    private static String executionSchema(DatabaseConfig target, Connection connection)
            throws SQLException {
        String configured = configuredSchema(target);
        if (configured != null) {
            return configured;
        }
        if (target.resolvedFamily() == DatabaseType.DialectFamily.MYSQL) {
            return target.getDatabaseName();
        }
        return connection.getSchema();
    }

    /**
     * The configured schema, or null when none is configured.
     *
     * <p>Oracle and DB2 fold an unquoted name to upper case. A quoted name is
     * case-sensitive by definition, so it keeps the case the administrator wrote.
     */
    private static String configuredSchema(DatabaseConfig target) {
        String schema = target.getSchemaName();
        if (schema == null || schema.isBlank()) {
            return null;
        }
        schema = schema.trim();
        if (schema.length() >= 2) {
            char first = schema.charAt(0);
            char last = schema.charAt(schema.length() - 1);
            if (first == '"' && last == '"') {
                return schema.substring(1, schema.length() - 1).replace("\"\"", "\"");
            }
            if (first == '`' && last == '`') {
                return schema.substring(1, schema.length() - 1);
            }
        }
        return target.resolvedFamily() == DatabaseType.DialectFamily.ORACLE
                || target.resolvedFamily() == DatabaseType.DialectFamily.DB2
                ? schema.toUpperCase(Locale.ROOT) : schema;
    }

    /**
     * Whether the source dialect gives a backslash a meaning inside a string literal.
     *
     * <p>MySQL (and its protocol-compatible presets) do; Oracle, DM, SQL Server and
     * PostgreSQL do not. Reading {@code DEFAULT 'C:\'} as an escaped quote merges the
     * remainder of a multi-clause statement into the literal, so the columns after it are
     * never seen — and the task closes without a replay.
     */
    boolean backslashEscapes(Project project) {
        if (project == null || project.getSourceDbId() == null
                || databaseConfigRepository == null) {
            return true;
        }
        DatabaseConfig source = databaseConfigRepository.findById(project.getSourceDbId())
                .orElse(null);
        return source == null
                || source.resolvedFamily() == DatabaseType.DialectFamily.MYSQL;
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
