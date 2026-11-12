package com.qqmu.jync.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Renders a change-log entry's stored 详情 in the page's language.
 *
 * <p>{@code change_log.details} is the sentence its writer built when the event happened, in
 * English: the column therefore stayed English while every other column followed the UI
 * language toggle. Rewriting the 46 writers to store keys would need a migration for the
 * historical rows and would change the duplicate-failure dedupe key. Matching the known shapes
 * back onto keys at render time leaves the stored data alone: old entries translate too, a
 * language switch applies immediately, and an unrecognized sentence — a driver's own message,
 * for instance — is returned exactly as the database said it, never half-translated.
 *
 * <p>Display only: nothing here feeds a decision, a comparison or the dedupe key.
 */
// The name is explicit because the templates look the bean up as
// {@code @changeLogDetailsLocalizer}: a component-scan slice and a test's @Import name the
// same class differently, and the template must not depend on which one loaded it.
@Component("changeLogDetailsLocalizer")
public class ChangeLogDetailsLocalizer {

    /** Returned by {@link #get} for a key a bundle does not define. */
    private static final String MISSING = "\u0000missing\u0000";

    // DOTALL: the detail is a driver message, which may wrap. Without it a wrapped cause fell
    // through untranslated.
    private static final Pattern CONSUMER_START_FAILED =
            Pattern.compile("^CDC consumer failed to start: (.+)$", Pattern.DOTALL);
    private static final Pattern CONSUMER_STARTED =
            Pattern.compile("^(\\w+) consumer started$");
    private static final Pattern CONSUMER_STOPPED =
            Pattern.compile("^(\\w+) consumer stopped$");
    private static final Pattern CONSUMER_LEASE_LOST =
            Pattern.compile("^(\\w+) (?:consumer|CDC) stopped: lease lost$");
    private static final Pattern CONSUMER_FAILED =
            Pattern.compile("^(\\w+) consumer failed: (.+)$", Pattern.DOTALL);
    private static final Pattern CDC_WINDOW = Pattern.compile(
            "^CDC window: (\\d+) upserts, (\\d+) deletes(?: \\(through (.+)\\))?$");
    private static final Pattern CDC_TARGET_REPAIRED = Pattern.compile(
            "^cdc target table repair: recreated target table (\\S+) and reloaded it from the"
                    + " source \\((\\d+) row\\(s\\) written\\); the CDC position was left"
                    + " untouched, so replay continues from the same point"
                    + "(?:; closed (\\d+) open skipped-DDL task\\(s\\) as completed by the"
                    + " rebuild)?$");
    private static final Pattern CDC_FLUSH_FAILED =
            Pattern.compile("^CDC transaction flush failed: (.+)$", Pattern.DOTALL);
    private static final String CDC_SCHEMA_MISMATCH = "CDC target is missing a column the source"
            + " has; the column is projected away until the target is aligned";
    private static final String SKIPPED_DDL_MARKER =
            "\nSkipped DDL (not applied to the target):\n";
    private static final Pattern OWN_EXCEPTION =
            Pattern.compile("^com\\.qqmu\\.jync\\.[\\w.$]+: (.*)$", Pattern.DOTALL);
    private static final Pattern CDC_TARGET_MISSING = Pattern.compile(
            "^cdc target table missing: source table (.+?) cannot be applied to target (.+?)"
                    + " because that table does not exist(?: \\(the target reported: (.+)\\))?$",
            Pattern.DOTALL);
    /** The verdict label that starts every row a parked consumer leaves behind. */
    private static final Pattern CDC_PARKED = Pattern.compile(
            "^(PURGED|SERVER_ID_CONFLICT|AUTH_FAILURE|SCHEMA_CONFLICT|XA_ORDERING|SCHEMA_DRIFT"
                    + "|TRANSACTION_TOO_LARGE|YSTREAM_BLOCKED|TARGET_TABLE_MISSING"
                    + "|SCHEMA_MISMATCH_STRIKE_OUT|DESERIALIZATION_STRIKE_OUT|APPLY_STRIKE_OUT"
                    + "|PARSE_STRIKE_OUT): (.+)$", Pattern.DOTALL);
    private static final Pattern CDC_TARGETED_REPAIR = Pattern.compile(
            "^targeted repair (REPAIRED|FAILED|CAP_REACHED|DISABLED|NOT_APPLICABLE): (.+)$",
            Pattern.DOTALL);
    private static final Pattern REPAIR_CAP_REACHED = Pattern.compile(
            "^the target table was missing in (\\d+) consecutive repair cycles \\(limit (\\d+)\\),"
                    + " so the cause is still present; fix it, then restart the project or"
                    + " re-baseline$");
    private static final Pattern REPAIR_STILL_MISSING = Pattern.compile(
            "^the target still lacks (.+) after the repair cycle$", Pattern.DOTALL);
    private static final Pattern REPAIR_ENUMERATE = Pattern.compile(
            "^could not enumerate the target tables: (.+)$", Pattern.DOTALL);

    private static final Pattern DATA_ROWS = Pattern.compile(
            "^(Initial load|Incremental sync): (\\d+) row\\(s\\) (inserted|updated)"
                    + "( \\(\\d+ scanned(?:, \\d+ already up to date)?\\))?"
                    + " via (\\w+) strategy(?: on (\\S+))?(.*)$");
    private static final Pattern DATA_SCANNED =
            Pattern.compile(" \\((\\d+) scanned(?:, (\\d+) already up to date)?\\)");
    private static final Pattern DATA_DELETED =
            Pattern.compile("^(\\d+) row\\(s\\) removed to match source$");
    private static final Pattern DATA_DELETION_SKIPPED = Pattern.compile(
            "^Deletion sync for (\\S+) is skipped: (.*?)\\. Rows deleted at the source stay on"
                    + " the target\\.(.*)$");
    private static final Pattern DELETE_SKIP_NO_PK =
            Pattern.compile("^it has no primary key, so rows cannot be matched$");
    private static final Pattern DELETE_SKIP_UNKNOWN_ROWS =
            Pattern.compile("^its row count could not be estimated$");
    private static final Pattern DELETE_SKIP_KEY_REWRITE = Pattern.compile(
            "^it has about (\\d+) rows, over sync\\.full-compare-max-rows=(\\d+)"
                    + " \\(a column rule rewrites its primary key, so keys can only be compared"
                    + " in memory\\)$");
    private static final Pattern DELETE_SKIP_FULL_COMPARE = Pattern.compile(
            "^it has about (\\d+) rows, over sync\\.full-compare-max-rows=(\\d+)$");
    private static final Pattern DELETE_SKIP_DELETE_COMPARE = Pattern.compile(
            "^it has about (\\d+) rows, over sync\\.delete-compare-max-rows=(\\d+)$");

    private static final Pattern AUDIT_RELOAD = Pattern.compile(
            "^Row count audit: target is missing (\\d+) row\\(s\\) already marked as synced"
                    + " \\((.*)\\)\\. Cursor cleared; the table will be fully reloaded\\.$");
    private static final Pattern AUDIT_BY_KEY = Pattern.compile(
            "^Row count audit: target was missing (\\d+) row\\(s\\) already marked as synced"
                    + " \\((.*)\\)\\. (\\d+) row\\(s\\) re-copied by key; the cursor was kept and"
                    + " no reload was needed\\.$");
    private static final Pattern AUDIT_SURPLUS = Pattern.compile(
            "^Row count audit: target has (\\d+) row\\(s\\) more than the source \\((.*)\\)\\."
                    + " Timestamp / identity polling cannot see physical deletes, so these rows"
                    + " stay on the target until deletion sync or log-based CDC removes them\\.$");
    private static final Pattern AUDIT_COUNTS = Pattern.compile(
            "^source (\\d+) vs target (\\d+) at or below cursor (.*)$");
    private static final Pattern AUDIT_CAUSE_RELOAD = Pattern.compile(
            "^ — row count audit: target was missing (\\d+) row\\(s\\) already marked as synced"
                    + " \\((.*)\\); cursor cleared and the table reloaded$");
    private static final Pattern AUDIT_CAUSE_BY_KEY = Pattern.compile(
            "^ — row count audit: target was missing (\\d+) row\\(s\\) already marked as synced"
                    + " \\((.*)\\); (\\d+) row\\(s\\) re-copied by key, cursor kept$");

    private static final Pattern DROPPED_TABLE = Pattern.compile("^Dropped table (\\S+)$");
    private static final Pattern ADDED_COLUMN =
            Pattern.compile("^Added column (\\S+) to (\\S+)$");
    private static final Pattern MODIFIED_COLUMN =
            Pattern.compile("^Modified column (\\S+) on (\\S+)$");
    private static final Pattern DROPPED_COLUMN =
            Pattern.compile("^Dropped column (\\S+) from (\\S+)$");
    private static final Pattern ADDED_MISSING_COLUMNS =
            Pattern.compile("^Added missing column\\(s\\) (.+) to (\\S+)$");
    private static final Pattern DROPPED_INDEX = Pattern.compile("^Dropped index (.+)$");
    private static final Pattern RECOVERED_INDEX =
            Pattern.compile("^Created previously-failed index (\\S+) on (\\S+)$");
    private static final Pattern CREATED_INDEX =
            Pattern.compile("^Created index (\\S+) on (\\S+)$");
    private static final Pattern RECREATED_INDEX =
            Pattern.compile("^Recreated index (\\S+) on (\\S+)$");
    private static final Pattern DROPPED_VIEW = Pattern.compile("^Dropped view (.+)$");
    private static final Pattern CREATED_VIEW = Pattern.compile("^Created view (.+)$");
    private static final Pattern REPLACED_VIEW = Pattern.compile("^Replaced view (.+)$");
    private static final Pattern ROUTINE =
            Pattern.compile("^(Created|Replaced|Dropped) (PROCEDURE|FUNCTION) (\\S+)$");
    private static final Pattern INDEX_NOT_CREATABLE = Pattern.compile(
            "^Index (.+) cannot be created on the target; data sync takes priority$");
    private static final Pattern UNSUPPORTED_COLUMN_CHANGE =
            Pattern.compile("^Unsupported column change (.+)$");
    private static final Pattern UNSUPPORTED_OBJECT_TYPE =
            Pattern.compile("^Unsupported object type (.+)$");

    private static final Pattern NEW_TABLE =
            Pattern.compile("^New table with (\\d+) column\\(s\\)$");
    private static final Pattern NEW_COLUMN = Pattern.compile("^New column (\\S+) (.*)$");
    private static final Pattern COLUMN_CHANGED =
            Pattern.compile("^Column (\\S+) changed: (.*) -> (.*)$");
    private static final Pattern COLUMN_COMMENT =
            Pattern.compile("^Column (\\S+) comment changed$");
    private static final Pattern COLUMN_REMOVED =
            Pattern.compile("^Column (\\S+) removed from source$");
    private static final Pattern NEW_INDEX = Pattern.compile("^New index (.+)$");
    private static final Pattern INDEX_REDEFINED = Pattern.compile("^Index (\\S+) redefined$");
    private static final Pattern INDEX_REMOVED = Pattern.compile("^Index (\\S+) removed$");
    private static final Pattern NEW_ROUTINE =
            Pattern.compile("^New (procedure|function)$");
    private static final Pattern ROUTINE_BODY_CHANGED =
            Pattern.compile("^(PROCEDURE|FUNCTION) body changed$");

    private final MessageSource messageSource;

    public ChangeLogDetailsLocalizer(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * The detail in the current request's language, or the stored text when the shape is
     * unknown. Null and blank come back untouched: a log row without details renders nothing.
     */
    public String localize(String details) {
        return localize(details, LocaleContextHolder.getLocale());
    }

    /** Same contract as {@link #localize(String)}, with the locale given explicitly. */
    public String localize(String details, Locale locale) {
        if (details == null || details.isBlank()) {
            return details;
        }
        String localized = match(details, locale == null ? Locale.getDefault() : locale);
        return localized == null ? details : localized;
    }

    /**
     * The localized sentence, or {@code null} when no rule recognizes the text (or when a
     * bundle value is missing — a bundle gap degrades to the stored English, never to
     * {@code ??log.detail...??} on a user's screen).
     */
    private String match(String details, Locale locale) {
        String text;
        if ((text = consumer(details, locale)) != null) {
            return text;
        }
        if ("baseline rebuilt".equals(details)) {
            return get(locale, "log.detail.baseline.rebuilt");
        }
        if ("baseline reset (task disabled)".equals(details)) {
            return get(locale, "log.detail.baseline.reset");
        }
        if ((text = structureTable(details, locale)) != null) {
            return text;
        }
        if ((text = structure(details, locale)) != null) {
            return text;
        }
        if ((text = data(details, locale)) != null) {
            return text;
        }
        return detector(details, locale);
    }

    /** CDC/LogMiner/PG/SQLServer/YStream consumer lifecycle lines. */
    private String consumer(String details, Locale locale) {
        Matcher m = CONSUMER_START_FAILED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.consumer.startFailed", m.group(1));
        }
        m = CONSUMER_STARTED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.consumer.started", m.group(1));
        }
        m = CONSUMER_LEASE_LOST.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.consumer.leaseLost", m.group(1));
        }
        m = CONSUMER_STOPPED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.consumer.stopped", m.group(1));
        }
        m = CONSUMER_FAILED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.consumer.failed", m.group(1), m.group(2));
        }
        m = CDC_WINDOW.matcher(details);
        if (m.matches()) {
            return m.group(3) == null
                    ? get(locale, "log.detail.cdc.window", m.group(1), m.group(2))
                    : get(locale, "log.detail.cdc.windowThrough", m.group(1), m.group(2),
                            m.group(3));
        }
        m = CDC_TARGET_REPAIRED.matcher(details);
        if (m.matches()) {
            String text = get(locale, "log.detail.cdc.targetRepaired", m.group(1), m.group(2));
            if (text != null && m.group(3) != null) {
                String closed = get(locale, "log.detail.cdc.targetRepairedClosed", m.group(3));
                if (closed != null) {
                    text += closed;
                }
            }
            return text;
        }
        m = CDC_FLUSH_FAILED.matcher(details);
        if (m.matches()) {
            return flushFailure(m.group(1), locale);
        }
        if (details.startsWith(CDC_SCHEMA_MISMATCH)) {
            String text = schemaMismatch(details.substring(CDC_SCHEMA_MISMATCH.length()), locale);
            if (text != null) {
                return text;
            }
        }
        m = CDC_PARKED.matcher(details);
        if (m.matches()) {
            return parked(m.group(1), m.group(2), locale);
        }
        if ("baseline reset; consumer restart failed".equals(details)) {
            return get(locale, "log.detail.cdc.baselineRestartFailed");
        }
        return null;
    }

    /** "CDC transaction flush failed: <cause>" plus the optional skipped-DDL tail. */
    private String flushFailure(String tail, Locale locale) {
        int ddlAt = tail.indexOf(SKIPPED_DDL_MARKER);
        String cause = failureCause(ddlAt < 0 ? tail : tail.substring(0, ddlAt), locale);
        String ddl = ddlAt < 0 ? ""
                : skippedDdl(tail.substring(ddlAt + SKIPPED_DDL_MARKER.length()), locale);
        String text = get(locale, "log.detail.cdc.flushFailed", cause);
        return text == null ? null : text + ddl;
    }

    /** The fixed schema-mismatch sentence, with the skipped DDL kept verbatim when present. */
    private String schemaMismatch(String tail, Locale locale) {
        String ddl;
        if (tail.isEmpty()) {
            ddl = "";
        } else if (tail.startsWith(SKIPPED_DDL_MARKER)) {
            ddl = skippedDdl(tail.substring(SKIPPED_DDL_MARKER.length()), locale);
        } else {
            return null;
        }
        String text = get(locale, "log.detail.cdc.schemaMismatch");
        return text == null ? null : text + ddl;
    }

    private String skippedDdl(String sql, Locale locale) {
        String text = get(locale, "log.detail.cdc.skippedDdl", sql);
        return text == null ? SKIPPED_DDL_MARKER + sql : text;
    }

    /**
     * The cause of a flush failure: our own exception wrapper and sentence are ours to
     * translate, everything after them is the driver's text and stays exactly as reported.
     */
    private String failureCause(String raw, Locale locale) {
        String text = stripOwnExceptionPrefix(raw);
        String translated = targetMissing(text, locale);
        return translated == null ? text : translated;
    }

    /** Peels {@code com.qqmu.jync.…Exception: } wrappers the storage format keeps. */
    private static String stripOwnExceptionPrefix(String text) {
        String current = text;
        for (int i = 0; i < 4; i++) {
            Matcher m = OWN_EXCEPTION.matcher(current);
            if (!m.matches()) {
                break;
            }
            current = m.group(1);
        }
        return current;
    }

    private String targetMissing(String text, Locale locale) {
        Matcher m = CDC_TARGET_MISSING.matcher(text);
        if (!m.matches()) {
            return null;
        }
        return m.group(3) == null
                ? get(locale, "log.detail.cdc.targetMissing", m.group(1), m.group(2))
                : get(locale, "log.detail.cdc.targetMissingCause", m.group(1), m.group(2),
                        m.group(3));
    }

    /** "&lt;VERDICT&gt;: &lt;cause&gt;" — the row a parked consumer leaves in the change log. */
    private String parked(String label, String cause, Locale locale) {
        String labelText = get(locale, "log.detail.cdc.label." + labelKey(label));
        return labelText == null ? null
                : get(locale, "log.detail.cdc.parked", labelText, parkedCause(cause, locale));
    }

    private String parkedCause(String cause, Locale locale) {
        Matcher m = CDC_TARGETED_REPAIR.matcher(cause);
        if (m.matches()) {
            String status = get(locale,
                    "log.detail.cdc.repairStatus." + repairStatusKey(m.group(1)));
            if (status == null) {
                return cause;
            }
            String reason = repairReason(m.group(2), locale);
            String text = get(locale, "log.detail.cdc.repair", status,
                    reason == null ? m.group(2) : reason);
            return text == null ? cause : text;
        }
        String sentence = sentence(cause, locale);
        return sentence == null ? cause : sentence;
    }

    private String repairReason(String reason, Locale locale) {
        Matcher m = REPAIR_CAP_REACHED.matcher(reason);
        if (m.matches()) {
            return get(locale, "log.detail.cdc.repairCapReached", m.group(1), m.group(2));
        }
        m = REPAIR_STILL_MISSING.matcher(reason);
        if (m.matches()) {
            return get(locale, "log.detail.cdc.repairStillMissing", m.group(1));
        }
        m = REPAIR_ENUMERATE.matcher(reason);
        if (m.matches()) {
            return get(locale, "log.detail.cdc.repairEnumerate", m.group(1));
        }
        return sentence(reason, locale);
    }

    /** Fixed sentences the CDC writers park with; {@code null} for an unknown shape. */
    private String sentence(String text, Locale locale) {
        String key = switch (text) {
            case "repeated binlog deserialization failures" -> "repeatedDeserialization";
            case "repeated PG failures" -> "repeatedPg";
            case "repeated LogMiner failures" -> "repeatedLogMiner";
            case "repeated SQLServer CDC failures" -> "repeatedSqlServer";
            case "repeated YStream failures" -> "repeatedYStream";
            case "the target kept rejecting rows for a column the source has" ->
                    "targetRejectsColumn";
            case "the target kept refusing to apply transactions" -> "targetRefusesTransaction";
            case "not a missing-target-table fault" -> "repairNotMissing";
            case "no project context" -> "repairNoContext";
            case "targeted repair only applies to relational targets" -> "repairRelationalOnly";
            case "data sync is off, so a reload could not restore any rows" -> "repairDataSyncOff";
            case "targeted repair is disabled (sync.cdc.target-table-repair-enabled=false)" ->
                    "repairDisabled";
            case "the source no longer has the table named by the fault" -> "repairSourceGone";
            case "the repair cycle did not succeed" -> "repairCycleFailed";
            case "targeted repair is not wired on this engine" -> "repairNotWired";
            default -> null;
        };
        return key == null ? null : get(locale, "log.detail.cdc.sentence." + key);
    }

    private static String labelKey(String label) {
        return switch (label) {
            case "PURGED" -> "purged";
            case "SERVER_ID_CONFLICT" -> "serverIdConflict";
            case "AUTH_FAILURE" -> "authFailure";
            case "SCHEMA_CONFLICT" -> "schemaConflict";
            case "XA_ORDERING" -> "xaOrdering";
            case "SCHEMA_DRIFT" -> "schemaDrift";
            case "TRANSACTION_TOO_LARGE" -> "transactionTooLarge";
            case "YSTREAM_BLOCKED" -> "ystreamBlocked";
            case "TARGET_TABLE_MISSING" -> "targetTableMissing";
            case "SCHEMA_MISMATCH_STRIKE_OUT" -> "schemaMismatchStrikeOut";
            case "DESERIALIZATION_STRIKE_OUT" -> "deserializationStrikeOut";
            case "APPLY_STRIKE_OUT" -> "applyStrikeOut";
            default -> "parseStrikeOut";
        };
    }

    private static String repairStatusKey(String status) {
        return switch (status) {
            case "REPAIRED" -> "repaired";
            case "CAP_REACHED" -> "capReached";
            case "DISABLED" -> "disabled";
            case "NOT_APPLICABLE" -> "notApplicable";
            default -> "failed";
        };
    }

    /**
     * "Created table X" / "Table already present: X" plus the three optional phrases the
     * writer appends. Parsed from the end because the phrases are fixed strings and the table
     * name is not.
     */
    private String structureTable(String details, Locale locale) {
        String key;
        String rest;
        if (details.startsWith("Created table ")) {
            key = "log.detail.structure.created";
            rest = details.substring("Created table ".length());
        } else if (details.startsWith("Table already present: ")) {
            key = "log.detail.structure.present";
            rest = details.substring("Table already present: ".length());
        } else {
            return null;
        }

        String deferred = null;
        String deferredSuffix = " secondary index(es) deferred until the initial load completes";
        if (rest.endsWith(deferredSuffix)) {
            int headEnd = rest.length() - deferredSuffix.length();
            int mark = rest.lastIndexOf(", ", headEnd - 1);
            if (mark < 0) {
                return null;
            }
            deferred = rest.substring(mark + 2, headEnd);
            rest = rest.substring(0, mark);
        }

        String indexes = null;
        String indexSuffix = " index(es)";
        if (rest.endsWith(indexSuffix)) {
            int headEnd = rest.length() - indexSuffix.length();
            int mark = rest.lastIndexOf(" with ", headEnd - 1);
            if (mark < 0) {
                return null;
            }
            indexes = rest.substring(mark + " with ".length(), headEnd);
            rest = rest.substring(0, mark);
        }

        String added = null;
        String addedMarker = ", added column(s) ";
        int addedAt = rest.indexOf(addedMarker);
        if (addedAt >= 0) {
            added = rest.substring(addedAt + addedMarker.length());
            rest = rest.substring(0, addedAt);
        }

        if (rest.isBlank()) {
            return null;
        }
        String head = get(locale, key, rest);
        String addedText = added == null ? "" : get(locale, "log.detail.structure.addedColumns", added);
        String indexText = indexes == null
                ? "" : get(locale, "log.detail.structure.indexCount", indexes);
        String deferredText = deferred == null
                ? "" : get(locale, "log.detail.structure.deferredIndexes", deferred);
        if (head == null || addedText == null || indexText == null || deferredText == null) {
            return null;
        }
        return head + addedText + indexText + deferredText;
    }

    /** Structure changes with a single fixed sentence around their arguments. */
    private String structure(String details, Locale locale) {
        Matcher m = DROPPED_TABLE.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.droppedTable", m.group(1));
        }
        m = ADDED_MISSING_COLUMNS.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.addedMissingColumns", m.group(1), m.group(2));
        }
        m = ADDED_COLUMN.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.addedColumn", m.group(1), m.group(2));
        }
        m = MODIFIED_COLUMN.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.modifiedColumn", m.group(1), m.group(2));
        }
        m = DROPPED_COLUMN.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.droppedColumn", m.group(1), m.group(2));
        }
        m = RECOVERED_INDEX.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.recoveredIndex", m.group(1), m.group(2));
        }
        m = CREATED_INDEX.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.createdIndex", m.group(1), m.group(2));
        }
        m = RECREATED_INDEX.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.recreatedIndex", m.group(1), m.group(2));
        }
        m = DROPPED_INDEX.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.droppedIndex", m.group(1));
        }
        m = DROPPED_VIEW.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.droppedView", m.group(1));
        }
        m = CREATED_VIEW.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.createdView", m.group(1));
        }
        m = REPLACED_VIEW.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.structure.replacedView", m.group(1));
        }
        m = ROUTINE.matcher(details);
        if (m.matches()) {
            String action = switch (m.group(1)) {
                case "Created" -> "createdRoutine";
                case "Replaced" -> "replacedRoutine";
                default -> "droppedRoutine";
            };
            return get(locale, "log.detail.structure." + action, m.group(2), m.group(3));
        }
        return switch (details) {
            case "DROP not permitted by project settings" ->
                    get(locale, "log.detail.structure.dropDenied");
            case "Column DROP not permitted by project settings" ->
                    get(locale, "log.detail.structure.dropDeniedColumn");
            case "Index DROP not permitted by project settings" ->
                    get(locale, "log.detail.structure.dropDeniedIndex");
            case "View DROP not permitted by project settings" ->
                    get(locale, "log.detail.structure.dropDeniedView");
            case "Routine DROP not permitted by project settings" ->
                    get(locale, "log.detail.structure.dropDeniedRoutine");
            case "Target dialect cannot alter column types in place" ->
                    get(locale, "log.detail.structure.alterUnsupported");
            case "No view definition available to apply" ->
                    get(locale, "log.detail.structure.noViewDefinition");
            case "No routine body available to apply" ->
                    get(locale, "log.detail.structure.noRoutineBody");
            default -> {
                Matcher u = UNSUPPORTED_COLUMN_CHANGE.matcher(details);
                if (u.matches()) {
                    yield get(locale, "log.detail.structure.unsupportedColumnChange", u.group(1));
                }
                u = UNSUPPORTED_OBJECT_TYPE.matcher(details);
                if (u.matches()) {
                    yield get(locale, "log.detail.structure.unsupportedObjectType", u.group(1));
                }
                u = INDEX_NOT_CREATABLE.matcher(details);
                yield u.matches()
                        ? get(locale, "log.detail.structure.indexCannotCreate", u.group(1))
                        : null;
            }
        };
    }

    /** Row-sync counts, deletions, the deletion-skip note and the row-count audit. */
    private String data(String details, Locale locale) {
        Matcher m = DATA_ROWS.matcher(details);
        if (m.matches()) {
            return dataRows(m, locale);
        }
        m = DATA_DELETED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.data.deleted", m.group(1));
        }
        m = DATA_DELETION_SKIPPED.matcher(details);
        if (m.matches()) {
            String key = m.group(3).isEmpty()
                    ? "log.detail.data.deleteSkipped" : "log.detail.data.deleteSkippedAdvice";
            String reason = deleteSkipReason(m.group(2).trim(), locale);
            return reason == null ? null : get(locale, key, m.group(1), reason);
        }
        m = AUDIT_RELOAD.matcher(details);
        if (m.matches()) {
            String counts = auditCounts(m.group(2), locale);
            return counts == null ? null
                    : get(locale, "log.detail.data.auditReload", m.group(1), counts);
        }
        m = AUDIT_BY_KEY.matcher(details);
        if (m.matches()) {
            String counts = auditCounts(m.group(2), locale);
            return counts == null ? null
                    : get(locale, "log.detail.data.auditByKey", m.group(1), counts, m.group(3));
        }
        m = AUDIT_SURPLUS.matcher(details);
        if (m.matches()) {
            String counts = auditCounts(m.group(2), locale);
            return counts == null ? null
                    : get(locale, "log.detail.data.auditSurplus", m.group(1), counts);
        }
        return null;
    }

    /**
     * One "N row(s) inserted (…) via X strategy on col" line. The parts are joined rather
     * than formatted in one key because each optional part is its own sentence fragment, with
     * the punctuation each language needs around it.
     */
    private String dataRows(Matcher m, Locale locale) {
        String phase = get(locale, "Initial load".equals(m.group(1))
                ? "log.detail.data.phase.initial" : "log.detail.data.phase.incremental");
        String verb = get(locale, "inserted".equals(m.group(3))
                ? "log.detail.data.verb.inserted" : "log.detail.data.verb.updated");
        if (phase == null || verb == null) {
            return null;
        }
        String rowCount = get(locale, "log.detail.data.rowCount", m.group(2), verb);
        String head = rowCount == null ? null : get(locale, "log.detail.data.line", phase, rowCount);

        String scanned = "";
        if (m.group(4) != null) {
            Matcher s = DATA_SCANNED.matcher(m.group(4));
            scanned = s.matches()
                    ? (s.group(2) == null
                            ? get(locale, "log.detail.data.scanned", s.group(1))
                            : get(locale, "log.detail.data.scannedUpToDate", s.group(1),
                                    s.group(2)))
                    : m.group(4);
        }
        String strategy = get(locale, "log.detail.data.strategy", m.group(5));
        String column = m.group(6) == null
                ? "" : get(locale, "log.detail.data.onColumn", m.group(6));
        String cause = m.group(7).isEmpty() ? "" : auditCause(m.group(7), locale);
        if (head == null || scanned == null || strategy == null || column == null
                || cause == null) {
            return null;
        }
        return head + scanned + strategy + column + cause;
    }

    /**
     * Localizes the "it has about N rows, over …" reason inside the deletion-skip note.
     * Returns the raw reason when it is a shape this class does not know.
     */
    private String deleteSkipReason(String reason, Locale locale) {
        Matcher m = DELETE_SKIP_NO_PK.matcher(reason);
        if (m.matches()) {
            return get(locale, "log.detail.data.skipNoPrimaryKey");
        }
        m = DELETE_SKIP_UNKNOWN_ROWS.matcher(reason);
        if (m.matches()) {
            return get(locale, "log.detail.data.skipUnknownRows");
        }
        m = DELETE_SKIP_KEY_REWRITE.matcher(reason);
        if (m.matches()) {
            return get(locale, "log.detail.data.skipOverFullCompareKeyRewrite", m.group(1),
                    m.group(2));
        }
        m = DELETE_SKIP_FULL_COMPARE.matcher(reason);
        if (m.matches()) {
            return get(locale, "log.detail.data.skipOverFullCompare", m.group(1), m.group(2));
        }
        m = DELETE_SKIP_DELETE_COMPARE.matcher(reason);
        if (m.matches()) {
            return get(locale, "log.detail.data.skipOverDeleteCompare", m.group(1), m.group(2));
        }
        return reason;
    }

    /** "source N vs target M at or below cursor C", or the raw text when it is another shape. */
    private String auditCounts(String raw, Locale locale) {
        Matcher m = AUDIT_COUNTS.matcher(raw);
        return m.matches()
                ? get(locale, "log.detail.data.auditCounts", m.group(1), m.group(2), m.group(3))
                : raw;
    }

    /** The audit note appended to a data entry as its cause. */
    private String auditCause(String raw, Locale locale) {
        Matcher m = AUDIT_CAUSE_BY_KEY.matcher(raw);
        if (m.matches()) {
            String counts = auditCounts(m.group(2), locale);
            return counts == null ? raw
                    : get(locale, "log.detail.data.auditCauseByKey", m.group(1), counts,
                            m.group(3));
        }
        m = AUDIT_CAUSE_RELOAD.matcher(raw);
        if (m.matches()) {
            String counts = auditCounts(m.group(2), locale);
            return counts == null ? raw
                    : get(locale, "log.detail.data.auditCauseReload", m.group(1), counts);
        }
        return raw;
    }

    /** Schema-monitor descriptions, shown on the same page and in the dashboard feed. */
    private String detector(String details, Locale locale) {
        Matcher m = NEW_TABLE.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.detect.newTable", m.group(1));
        }
        m = COLUMN_CHANGED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.detect.columnChanged", m.group(1), m.group(2),
                    m.group(3));
        }
        m = COLUMN_COMMENT.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.detect.columnComment", m.group(1));
        }
        m = COLUMN_REMOVED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.detect.columnRemoved", m.group(1));
        }
        m = NEW_COLUMN.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.detect.newColumn", m.group(1), m.group(2));
        }
        m = NEW_INDEX.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.detect.newIndex", m.group(1));
        }
        m = INDEX_REDEFINED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.detect.indexRedefined", m.group(1));
        }
        m = INDEX_REMOVED.matcher(details);
        if (m.matches()) {
            return get(locale, "log.detail.detect.indexRemoved", m.group(1));
        }
        m = NEW_ROUTINE.matcher(details);
        if (m.matches()) {
            return get(locale, "procedure".equals(m.group(1))
                    ? "log.detail.detect.newProcedure" : "log.detail.detect.newFunction");
        }
        m = ROUTINE_BODY_CHANGED.matcher(details);
        if (m.matches()) {
            return get(locale, "PROCEDURE".equals(m.group(1))
                    ? "log.detail.detect.procedureBody" : "log.detail.detect.functionBody");
        }
        return switch (details) {
            case "Table comment changed" -> get(locale, "log.detail.detect.tableComment");
            case "Table no longer exists in source" -> get(locale, "log.detail.detect.tableGone");
            case "View definition changed" -> get(locale, "log.detail.detect.viewChanged");
            case "View no longer exists in source" -> get(locale, "log.detail.detect.viewGone");
            case "Routine no longer exists in source" ->
                    get(locale, "log.detail.detect.routineGone");
            default -> null;
        };
    }

    /** One bundle lookup; {@code null} when the bundle does not define the key. */
    private String get(Locale locale, String key, Object... args) {
        String text = messageSource.getMessage(key, args, MISSING, locale);
        return MISSING.equals(text) ? null : text;
    }
}
