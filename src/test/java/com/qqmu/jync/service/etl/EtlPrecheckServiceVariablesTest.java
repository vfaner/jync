package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * The precheck must probe the SQL the run would execute, not the placeholder text: otherwise a
 * green precheck and a failing run disagree — exactly what the precheck exists to prevent.
 */
class EtlPrecheckServiceVariablesTest {

    private static final String TARGET_URL =
            "jdbc:h2:mem:precheck-var-dst-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-03-05T12:00:00Z"), ZoneId.of("UTC"));

    private final DatabaseConfigService configService = mock(DatabaseConfigService.class);
    private final EtlConnectionProvider connections =
            config -> DriverManager.getConnection(TARGET_URL, "sa", "");
    private CapturingSource source;
    private EtlPrecheckService service;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
        }
        source = new CapturingSource();
        service = new EtlPrecheckService(configService, mock(DataSourceManager.class),
                (config, provider) -> source, CLOCK);
    }

    @Test
    void theProbeSeesTheResolvedSql() {
        EtlTask task = task("SELECT id FROM src WHERE tenant = '${tenant}' AND dt = '${date}'");
        task.setVariables("tenant=acme");
        task.setPreSql("DELETE FROM ${targetTable} WHERE dt = '${yesterday}'");
        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("dt >= '${month}-01'");

        service.check(task, h2(1L), h2(2L), connections);

        assertThat(source.seen.getSourceSql())
                .isEqualTo("SELECT id FROM src WHERE tenant = 'acme' AND dt = '2026-03-05'");
        assertThat(source.seen.getPreSql())
                .isEqualTo("DELETE FROM dst WHERE dt = '2026-03-04'");
        assertThat(source.seen.getRangeCondition()).isEqualTo("dt >= '2026-03-01'");
        // The form the operator is looking at keeps its placeholders.
        assertThat(task.getSourceSql())
                .isEqualTo("SELECT id FROM src WHERE tenant = '${tenant}' AND dt = '${date}'");
    }

    /**
     * A definition that expands to nothing leaves the range condition empty. The run refuses
     * that as a program defect before opening anything, so a green precheck would teach the
     * operator the wrong lesson.
     */
    @Test
    void aDefinitionThatEmptiesTheRangeConditionIsReported() {
        EtlTask task = task("SELECT id FROM src");
        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("${cond}");
        task.setVariables("cond=");

        EtlPrecheckResult result = service.check(task, h2(1L), h2(2L), connections);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).anySatisfy(check -> {
            assertThat(check.level()).isEqualTo(EtlPrecheckResult.Level.ERROR);
            assertThat(check.code()).isEqualTo("error.etl.range.required");
        });
    }

    @Test
    void aVariableThatCompletesALimitIsReportedLikeTheRunWouldRefuseIt() {
        // The raw form has a placeholder; the resolved form carries LIMIT. The run refuses
        // the latter for a resumable mode, so the precheck must not stay green.
        EtlTask task = task("SELECT id FROM src WHERE x = ${v}");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");
        task.setVariables("v=1 LIMIT 10");

        EtlPrecheckResult result = service.check(task, h2(1L), h2(2L), connections);

        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).anySatisfy(check ->
                assertThat(check.code()).isEqualTo("error.etl.resume.limit"));
    }

    @Test
    void aMongoSourceSqlIsNotResolved() {
        EtlTask task = task("not sql ${nope}");
        task.setSourceCollection("orders");

        service.check(task, mongo(1L), h2(2L), connections);

        assertThat(source.seen.getSourceSql()).isEqualTo("not sql ${nope}");
    }

    @Test
    void thePublicEntryRefusesAnUnknownVariableBeforeOpeningAnything() {
        EtlTask task = task("SELECT id FROM src WHERE dt = '${nope}'");
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        when(configService.findById(1L)).thenReturn(Optional.of(h2(1L)));
        when(configService.findById(2L)).thenReturn(Optional.of(h2(2L)));

        assertThatThrownBy(() -> service.check(task, AccessGuard.system()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.unknown:nope");
    }

    // --- helpers ------------------------------------------------------------------------

    private static EtlTask task(String sql) {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceSql(sql);
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }

    private static DatabaseConfig h2(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.H2);
        return config;
    }

    private static DatabaseConfig mongo(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MONGODB_NATIVE);
        return config;
    }

    private static final class CapturingSource implements EtlSource {
        private EtlTask seen;

        @Override
        public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
            throw new UnsupportedOperationException("not used by the precheck");
        }

        @Override
        public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) {
            seen = task;
            return new EtlSourceProbe(List.of(
                    new EtlSourceColumn("id", Types.INTEGER, "Int32", null, null, true)),
                    -1, List.of());
        }

        @Override
        public void close() {
        }
    }
}
