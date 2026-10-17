package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * CDC writes must apply the same column rules as the polling engine — an upsert and the
 * matching delete have to agree on the transformed key, or the target row leaks.
 */
class CdcEventApplierColumnCleanserTest {

    private Connection target;
    private CdcEventApplier applier;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:cdc_cleanse;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("CREATE TABLE CODES (CODE VARCHAR(32) PRIMARY KEY, LABEL VARCHAR(64))");
        }
        SyncConfig config = new SyncConfig();
        config.setColumnRules(List.of(
                rule("codes", "code", ColumnRuleType.STRIP_PREFIX, "tmp_", null),
                rule("codes", "label", ColumnRuleType.TRIM, null, null),
                rule("codes", "label", ColumnRuleType.UPPER, null, null)));
        SyncContext ctx = SyncContext.builder()
                .config(config)
                .targetDialect(new GenericSqlDialect())
                .batchSize(2)
                .build();
        applier = new CdcEventApplier(ctx, new CdcRuntimeStats());
        target.setAutoCommit(false);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        target.close();
    }

    @Test
    void anUpsertWritesTheCleansedValues() throws SQLException {
        applier.applyUpserts(target, binding(), List.of(row("tmp_A", "  alice  ")));
        target.commit();

        assertThat(labels()).containsExactly("A=ALICE");
    }

    @Test
    void aDeleteTransformsTheKeyExactlyLikeTheUpsertDid() throws SQLException {
        applier.applyUpserts(target, binding(), List.of(row("tmp_A", "alice")));
        target.commit();

        applier.applyDeletes(target, binding(), List.of(row("tmp_A", null)));
        target.commit();

        assertThat(labels()).isEmpty();
    }

    private static ColumnRule rule(String table, String column, ColumnRuleType type,
                                   String arg, String replacement) {
        ColumnRule rule = new ColumnRule();
        rule.setTable(table);
        rule.setColumn(column);
        rule.setType(type);
        rule.setArg(arg);
        rule.setReplacement(replacement);
        return rule;
    }

    private static CdcTableBinding binding() {
        ColumnMeta code = new ColumnMeta();
        code.setName("code");
        code.setJdbcType(Types.VARCHAR);
        code.setPrimaryKey(true);
        ColumnMeta label = new ColumnMeta();
        label.setName("label");
        label.setJdbcType(Types.VARCHAR);

        TableMeta meta = new TableMeta();
        meta.setName("codes");
        meta.setColumns(List.of(code, label));
        meta.setPrimaryKeys(List.of("code"));
        return CdcTableBinding.of("src", "codes", meta, "codes", null);
    }

    private static Map<String, Object> row(String code, String label) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("label", label);
        return row;
    }

    private List<String> labels() throws SQLException {
        List<String> out = new java.util.ArrayList<>();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT code || '=' || label FROM CODES ORDER BY code")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
