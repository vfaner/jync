package com.qqmu.jync.service.etl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;

/**
 * The {@code ${name}} placeholders an ETL task may write in its SQL.
 *
 * <p>A reference is an ASCII identifier between braces; every other {@code ${...}} stays
 * literal, so a vendor's own syntax or a customer's string is not rewritten by accident. A
 * definition is one {@code name=value} line in the task's variables box. Values are inserted
 * verbatim — no quoting, no escaping — and the result is never scanned again: a value that
 * happens to contain {@code ${date}} reaches the database as typed, not as today's date.
 *
 * <p>Resolution happens once at the start of a run and a precheck, into a detached copy of the
 * task. The entity is never changed: a saved task keeps its placeholders, so the next day's run
 * and the form that shows it see the same text the operator wrote.
 */
final class EtlSqlVariables {

    static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    static final Set<String> BUILT_INS = Set.of(
            "date", "datetime", "yesterday", "month", "year", "taskName", "targetTable",
            "runIndex");

    static final int MAX_NAME_LENGTH = 128;
    static final int MAX_VALUE_LENGTH = 4000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");

    /** Case-folded so {@code DATE=...} is refused with the same message as {@code date=...}. */
    private static final Set<String> BUILT_INS_FOLDED = BUILT_INS.stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private EtlSqlVariables() {
    }

    /** The custom definitions, validated; blank lines are skipped and line numbers are 1-based. */
    static Map<String, String> parseDefinitions(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return values;
        }
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String lineNumber = Integer.toString(i + 1);
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw new EtlVariableException("error.etl.var.line", lineNumber);
            }
            String name = line.substring(0, equals).trim();
            if (name.isEmpty()) {
                throw new EtlVariableException("error.etl.var.line", lineNumber);
            }
            if (!NAME.matcher(name).matches()) {
                throw new EtlVariableException("error.etl.var.name", name);
            }
            if (name.length() > MAX_NAME_LENGTH) {
                throw new EtlVariableException("error.etl.var.nameTooLong", name);
            }
            if (BUILT_INS_FOLDED.contains(name.toLowerCase(Locale.ROOT))) {
                throw new EtlVariableException("error.etl.var.reserved", name);
            }
            String value = line.substring(equals + 1).trim();
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new EtlVariableException("error.etl.var.tooLong", name);
            }
            if (values.containsKey(name)) {
                throw new EtlVariableException("error.etl.var.duplicate", name);
            }
            values.put(name, value);
        }
        return values;
    }

    /**
     * Refuses a reference no definition or built-in can answer. The Mongo collection name and
     * filter are not scanned: they are not SQL, and the native source never reads SQL.
     *
     * <p>The range condition is scanned only by a range overwrite. The form hides that field
     * for the other modes but still submits whatever was typed in it, so an unknown name left
     * there must not refuse a save over a statement the mode never executes.
     */
    static void validateReferences(EtlTask task, boolean sqlSource) {
        Set<String> known = new LinkedHashSet<>(BUILT_INS);
        known.addAll(parseDefinitions(task.getVariables()).keySet());
        for (String sql : sqlTexts(task, sqlSource)) {
            if (sql == null) {
                continue;
            }
            Matcher matcher = REFERENCE.matcher(sql);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!known.contains(name)) {
                    throw new EtlVariableException("error.etl.var.unknown", name);
                }
            }
        }
    }

    /** The built-in clock values and the task's definitions, ready to substitute. */
    static Map<String, String> values(EtlTask task, Clock clock, DatabaseConfig targetConfig) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("date", now.format(DATE));
        values.put("datetime", now.format(DATETIME));
        values.put("yesterday", now.minusDays(1).format(DATE));
        values.put("month", now.format(MONTH));
        values.put("year", now.format(YEAR));
        values.put("taskName", task.getName() == null ? "" : task.getName().trim());
        values.put("targetTable", targetName(task, targetConfig));
        // Completed successful runs before this one; a null column predates the counter.
        values.put("runIndex",
                Integer.toString(task.getRunIndex() == null ? 0 : task.getRunIndex()));
        values.putAll(parseDefinitions(task.getVariables()));
        return values;
    }

    /**
     * The table the run writes, schema-qualified the way the runner qualifies it. A pre-sql that
     * says {@code DELETE FROM ${targetTable}} must not reach a same-named table in the session's
     * default schema when the connection, not the task, names the schema.
     */
    private static String targetName(EtlTask task, DatabaseConfig targetConfig) {
        String table = task.getTargetTable();
        if (table == null || table.isBlank()) {
            return "";
        }
        return targetConfig == null ? table.trim() : EtlSqlSupport.qualified(targetConfig, table);
    }

    /**
     * Replaces every known reference in one pass. A replacement is never re-scanned, and the
     * caller has already refused unknown names, but an unknown name still fails here so a path
     * that skipped validation (an imported config package, for one) cannot leave it in SQL.
     */
    static String substitute(String sql, Map<String, String> values) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = REFERENCE.matcher(sql);
        StringBuilder out = new StringBuilder(sql.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new EtlVariableException("error.etl.var.unknown", name);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * A detached copy with the SQL resolved, so the entity the form and the scheduler hold keeps
     * its placeholders. Only the fields a run reads are carried; nothing here is persisted.
     */
    static EtlTask resolve(EtlTask task, boolean sqlSource, Clock clock,
                           DatabaseConfig targetConfig) {
        Map<String, String> values = values(task, clock, targetConfig);
        EtlTask resolved = new EtlTask();
        resolved.setId(task.getId());
        resolved.setName(task.getName());
        resolved.setBatchId(task.getBatchId());
        resolved.setSourceCollection(task.getSourceCollection());
        resolved.setSourceFilter(task.getSourceFilter());
        resolved.setSourceSql(sqlSource
                ? substitute(task.getSourceSql(), values) : task.getSourceSql());
        resolved.setPreSql(substitute(task.getPreSql(), values));
        resolved.setPostSql(substitute(task.getPostSql(), values));
        resolved.setTargetTable(task.getTargetTable());
        resolved.setWriteMode(task.getWriteMode());
        // The condition only runs inside a range overwrite's DELETE; elsewhere it is copied
        // as typed rather than resolved, so a stale value cannot fail the run.
        resolved.setRangeCondition(task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE
                ? substitute(task.getRangeCondition(), values) : task.getRangeCondition());
        resolved.setResumeKeyColumn(task.getResumeKeyColumn());
        resolved.setUpsertKeyColumns(task.getUpsertKeyColumns());
        resolved.setTimeoutSeconds(task.getTimeoutSeconds());
        return resolved;
    }

    private static Iterable<String> sqlTexts(EtlTask task, boolean sqlSource) {
        List<String> texts = new ArrayList<>();
        if (sqlSource) {
            texts.add(task.getSourceSql());
        }
        texts.add(task.getPreSql());
        texts.add(task.getPostSql());
        if (task.getWriteMode() == EtlWriteMode.RANGE_OVERWRITE) {
            texts.add(task.getRangeCondition());
        }
        return texts;
    }
}
