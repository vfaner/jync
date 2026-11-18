package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;

/**
 * Placeholders in a task's SQL are replaced with the built-in clock values and the task's own
 * {@code name=value} definitions, once per run and before any connection is opened.
 *
 * <p>The substitution is one pass: a value that itself contains {@code ${...}} stays literal,
 * because a customer's data or a custom value is not a template.
 */
class EtlSqlVariablesTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-03-04T23:30:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void theBuiltInsComeFromTheClockAtItsOwnZone() {
        EtlTask task = task("SELECT * FROM t");

        assertThat(EtlSqlVariables.values(task, CLOCK, target())).contains(
                Map.entry("date", "2026-03-05"),
                Map.entry("datetime", "2026-03-05 07:30:00"),
                Map.entry("yesterday", "2026-03-04"),
                Map.entry("month", "2026-03"),
                Map.entry("year", "2026"));
    }

    @Test
    void taskNameAndTargetTableComeFromTheTask() {
        EtlTask task = task("SELECT * FROM t");
        task.setName("daily-orders");
        task.setTargetTable("ods.orders");

        assertThat(EtlSqlVariables.values(task, CLOCK, target()))
                .containsEntry("taskName", "daily-orders")
                .containsEntry("targetTable", "ods.orders");
    }

    /**
     * The copy writes {@code SCHEMA.table} when the target connection names a schema, so the
     * built-in must name that same table: pre-sql such as {@code DELETE FROM ${targetTable}}
     * would otherwise hit a same-named table in the session's default schema.
     */
    @Test
    void theTargetTableIsQualifiedByTheTargetConnectionsSchema() {
        EtlTask task = task("SELECT * FROM t");
        task.setTargetTable("orders");
        DatabaseConfig target = target();
        target.setSchemaName("ODS");

        assertThat(EtlSqlVariables.values(task, CLOCK, target))
                .containsEntry("targetTable", "ODS.orders");

        // A table the operator already qualified is left exactly as typed.
        task.setTargetTable("dwh.orders");
        assertThat(EtlSqlVariables.values(task, CLOCK, target))
                .containsEntry("targetTable", "dwh.orders");
    }

    @Test
    void aCustomDefinitionIsInsertedVerbatim() {
        EtlTask task = task("SELECT * FROM t WHERE tenant = ${tenant}");
        task.setVariables("tenant='acme'");

        String resolved = EtlSqlVariables.resolve(task, true, CLOCK, target()).getSourceSql();

        assertThat(resolved).isEqualTo("SELECT * FROM t WHERE tenant = 'acme'");
    }

    @Test
    void aReplacementIsNotScannedAgain() {
        EtlTask task = task("SELECT * FROM t WHERE dt = ${date} AND note = ${note}");
        // The value contains a built-in reference; it must reach the SQL as typed, not as
        // today's date, because substitution is a single pass.
        task.setVariables("note=${date}");

        String resolved = EtlSqlVariables.resolve(task, true, CLOCK, target()).getSourceSql();

        assertThat(resolved).isEqualTo(
                "SELECT * FROM t WHERE dt = 2026-03-05 AND note = ${date}");
    }

    /** A value is data, not a regex replacement: {@code $} and {@code \} must survive intact. */
    @Test
    void aValueWithDollarAndBackslashIsInsertedLiterally() {
        EtlTask task = task("SELECT '${v}'");
        task.setVariables("v=a$b\\c");

        assertThat(EtlSqlVariables.resolve(task, true, CLOCK, target()).getSourceSql())
                .isEqualTo("SELECT 'a$b\\c'");
    }

    @Test
    void onlyIdentifierShapedReferencesArePlaceholders() {
        EtlTask task = task("SELECT '${1x}', '${a-b}', '${}', '${ date }', '$date'");

        String resolved = EtlSqlVariables.resolve(task, true, CLOCK, target()).getSourceSql();

        assertThat(resolved).isEqualTo(
                "SELECT '${1x}', '${a-b}', '${}', '${ date }', '$date'");
    }

    @Test
    void namesAreCaseSensitive() {
        EtlTask task = task("SELECT '${Date}'");

        assertThatThrownBy(() -> EtlSqlVariables.validateReferences(task, true))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.unknown:Date");
    }

    @Test
    void anUnknownReferenceIsRefusedWithItsName() {
        EtlTask task = task("SELECT * FROM t WHERE dt = ${nope}");

        assertThatThrownBy(() -> EtlSqlVariables.validateReferences(task, true))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.unknown:nope");
    }

    @Test
    void everySqlLocationIsSubstituted() {
        EtlTask task = task("SELECT * FROM t WHERE dt = '${date}'");
        task.setPreSql("DELETE FROM ${targetTable} WHERE dt = '${yesterday}'");
        task.setPostSql("UPDATE ${targetTable} SET loaded = '${datetime}'");
        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("dt >= '${month}-01'");

        EtlTask resolved = EtlSqlVariables.resolve(task, true, CLOCK, target());

        assertThat(resolved.getSourceSql()).isEqualTo("SELECT * FROM t WHERE dt = '2026-03-05'");
        assertThat(resolved.getPreSql()).isEqualTo("DELETE FROM t WHERE dt = '2026-03-04'");
        assertThat(resolved.getPostSql()).isEqualTo(
                "UPDATE t SET loaded = '2026-03-05 07:30:00'");
        assertThat(resolved.getRangeCondition()).isEqualTo("dt >= '2026-03-01'");
    }

    /**
     * Only a range overwrite executes the range condition. Switching mode leaves whatever was
     * typed in that (now hidden) field in the form, so an unknown name there must not refuse a
     * save or a run that never reads it — the same stance {@link EtlRunner} takes for the
     * fields the active mode does not execute.
     */
    @Test
    void theRangeConditionOnlyMattersToARangeOverwrite() {
        EtlTask task = task("SELECT * FROM t");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setRangeCondition("dt >= '${nope}'");

        assertThatCode(() -> EtlSqlVariables.validateReferences(task, true))
                .doesNotThrowAnyException();
        assertThat(EtlSqlVariables.resolve(task, true, CLOCK, target()).getRangeCondition())
                .isEqualTo("dt >= '${nope}'");

        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);
        assertThatThrownBy(() -> EtlSqlVariables.validateReferences(task, true))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.unknown:nope");
    }

    @Test
    void theOriginalTaskIsNeverChanged() {
        EtlTask task = task("SELECT * FROM t WHERE dt = '${date}'");
        task.setPreSql("DELETE FROM t WHERE dt = '${date}'");

        EtlSqlVariables.resolve(task, true, CLOCK, target());

        assertThat(task.getSourceSql()).isEqualTo("SELECT * FROM t WHERE dt = '${date}'");
        assertThat(task.getPreSql()).isEqualTo("DELETE FROM t WHERE dt = '${date}'");
    }

    @Test
    void aMongoTaskKeepsItsSourceSqlButStillSubstitutesTheOtherSql() {
        EtlTask task = task("not SQL, ${nope} is fine here");
        task.setPreSql("DELETE FROM '${targetTable}'");

        EtlTask resolved = EtlSqlVariables.resolve(task, false, CLOCK, target());

        assertThat(resolved.getSourceSql()).isEqualTo("not SQL, ${nope} is fine here");
        assertThat(resolved.getPreSql()).isEqualTo("DELETE FROM 't'");
        assertThatCode(() -> EtlSqlVariables.validateReferences(task, false))
                .doesNotThrowAnyException();
    }

    @Test
    void anExecutionCopyCarriesEverythingTheRunnerReads() {
        EtlTask task = task("SELECT * FROM t");
        task.setId(9L);
        task.setSourceCollection("orders");
        task.setSourceFilter("{\"a\": 1}");
        task.setWriteMode(EtlWriteMode.UPSERT);
        task.setResumeKeyColumn("id");
        task.setUpsertKeyColumns("id, tenant");
        task.setTimeoutSeconds(30);

        EtlTask resolved = EtlSqlVariables.resolve(task, true, CLOCK, target());

        assertThat(resolved.getId()).isEqualTo(9L);
        assertThat(resolved.getSourceCollection()).isEqualTo("orders");
        assertThat(resolved.getSourceFilter()).isEqualTo("{\"a\": 1}");
        assertThat(resolved.getWriteMode()).isEqualTo(EtlWriteMode.UPSERT);
        assertThat(resolved.getResumeKeyColumn()).isEqualTo("id");
        assertThat(resolved.getUpsertKeyColumns()).isEqualTo("id, tenant");
        assertThat(resolved.getTargetTable()).isEqualTo("t");
        assertThat(resolved.getTimeoutSeconds()).isEqualTo(30);
    }

    // --- definitions --------------------------------------------------------------------

    @Test
    void blankLinesAndSurroundingSpacesAreIgnored() {
        EtlTask task = task("SELECT ${a} ${b}");
        task.setVariables("\n   \n  a = 1  \n\n b = two words \n");

        assertThat(EtlSqlVariables.parseDefinitions(task.getVariables()))
                .containsExactly(Map.entry("a", "1"), Map.entry("b", "two words"));
    }

    @Test
    void aLineWithoutAnEqualsIsReportedWithItsNumber() {
        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions("a=1\nnonsense\nb=2"))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.line:2");
    }

    @Test
    void anInvalidNameIsRefused() {
        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions("1abc=x"))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.name:1abc");
        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions("a-b=x"))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.name:a-b");
        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions("=x"))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.line:1");
    }

    @Test
    void anOverlongNameIsRefused() {
        String name = "a".repeat(EtlSqlVariables.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions(name + "=x"))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.nameTooLong:" + name);
    }

    @Test
    void anOverlongValueIsRefused() {
        String value = "x".repeat(EtlSqlVariables.MAX_VALUE_LENGTH + 1);

        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions("a=" + value))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.tooLong:a");
    }

    @Test
    void aRepeatedNameIsRefused() {
        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions("a=1\na=2"))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.duplicate:a");
    }

    @Test
    void aBuiltInNameCannotBeRedefined() {
        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions("date=2020-01-01"))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.reserved:date");
        // Case does not save it: DATE and date would both read as the same thing to an operator.
        assertThatThrownBy(() -> EtlSqlVariables.parseDefinitions("DATE=2020-01-01"))
                .isInstanceOf(EtlVariableException.class)
                .hasMessage("error.etl.var.reserved:DATE");
    }

    @Test
    void onlyTheFirstEqualsSeparatesTheDefinition() {
        EtlTask task = task("SELECT '${a}'");
        task.setVariables("a=x=y");

        assertThat(EtlSqlVariables.resolve(task, true, CLOCK, target()).getSourceSql())
                .isEqualTo("SELECT 'x=y'");
    }

    // --- helpers ------------------------------------------------------------------------

    private static EtlTask task(String sourceSql) {
        EtlTask task = new EtlTask();
        task.setSourceSql(sourceSql);
        task.setTargetTable("t");
        return task;
    }

    private static DatabaseConfig target() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.H2);
        return config;
    }
}
