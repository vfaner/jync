package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * v1.9.0: PG 逻辑 CDC 的门禁按方言家族判，不再按字面产品名。家族成员能否真正使用
 * 由连接时的能力探测决定（见 PgLogicalReplicationCdcProbe）。
 */
class CaptureStrategyParserTest {

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {
            "POSTGRESQL", "OPENGAUSS", "GAUSSDB", "KINGBASE", "RASESQL",
            "SEABOXSQL", "HIGHGO", "VASTBASE", "OSCAR"})
    void everyPostgresFamilyPresetAcceptsPgLogicalCdc(DatabaseType type) {
        assertThat(CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC", type))
                .isEqualTo(CaptureStrategy.PG_LOGICAL_CDC);
    }

    @Test
    void nonPostgresFamilyStillRejectsPgLogicalCdc() {
        assertThatThrownBy(() ->
                CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC", DatabaseType.MYSQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.postgres");
        assertThatThrownBy(() ->
                CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC", DatabaseType.ORACLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.postgres");
    }

    @Test
    void otherStrategiesKeepTheirLiteralGates() {
        assertThatThrownBy(() ->
                CaptureStrategyParser.parseRequested("BINLOG_CDC", DatabaseType.OPENGAUSS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.mysql");
        assertThatThrownBy(() ->
                CaptureStrategyParser.parseRequested("LOGMINER_CDC", DatabaseType.DM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.oracle");
    }

    @Test
    void blankRequestLeavesTheStrategyAlone() {
        assertThat(CaptureStrategyParser.parseRequested("  ", DatabaseType.MYSQL)).isNull();
    }

    // —— v1.9.0 follow-up: CUSTOM connections declare their family ——

    /**
     * A CUSTOM connection is how a PG-compatible product without a preset (IvorySQL,
     * PolarDB-PG, MogDB, …) is added, and the rest of the stack — dialect, type mapping,
     * metadata reader — already follows {@code DatabaseConfig.resolvedFamily()}. The CDC
     * gate must agree, or the one engine that verifies itself by capability stays closed
     * to exactly the products it was built to accept.
     */
    @Test
    void aCustomConnectionThatDeclaresThePostgresFamilyAcceptsPgLogicalCdc() {
        assertThat(CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.POSTGRES))
                .isEqualTo(CaptureStrategy.PG_LOGICAL_CDC);
    }

    @Test
    void aCustomConnectionWithoutADeclaredFamilyStillRejectsPgLogicalCdc() {
        assertThatThrownBy(() -> CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.postgres");
    }

    /** The declared family unlocks only the capability-probed engine, never the literal ones. */
    @Test
    void aDeclaredFamilyDoesNotUnlockTheLiteralEngines() {
        assertThatThrownBy(() -> CaptureStrategyParser.parseRequested("BINLOG_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.MYSQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.mysql");
        assertThatThrownBy(() -> CaptureStrategyParser.parseRequested("LOGMINER_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.ORACLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.oracle");
        assertThatThrownBy(() -> CaptureStrategyParser.parseRequested("SQLSERVER_CDC",
                DatabaseType.CUSTOM, DatabaseType.DialectFamily.SQLSERVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.sqlserver");
    }

    /** The two-arg form keeps deciding by the preset's own family (CUSTOM → GENERIC). */
    @Test
    void theTwoArgFormStillRejectsCustomWithoutADeclaredFamily() {
        assertThatThrownBy(() -> CaptureStrategyParser.parseRequested("PG_LOGICAL_CDC",
                DatabaseType.CUSTOM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cdc.not.postgres");
    }
}
