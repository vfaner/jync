package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.util.TargetTableVerifier;

/**
 * 目标端预建的表少一列时，结构同步应当把列补上，而不是把这张表永远卡住。
 *
 * <p>实测事故：源库 TEACHER 多了 gender，目标库的 TEACHER 是早先建好的、没有这一列。结构
 * 同步发出的 CREATE 打在已存在的表上（ORA-00955），失败又扣住快照，于是每轮重发、每轮失
 * 败；数据侧同时报「missing column gender」，并给出一句做不到的建议 —— 「let structure
 * sync recreate it」，因为它只会 CREATE，不会补列。缺列是纯增量差异，补列不动任何已存在
 * 的行，是这台机器唯一能自己修好的形态问题；类型不符、身份列不合规仍然交给人。
 */
class StructureSyncServiceMissingColumnTest {

    private Connection target;
    private StructureSyncService structure;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection("jdbc:h2:mem:add_col;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T");
            // 预建的目标表：少 NAME/AMOUNT，另外多一个源端没有的 LEGACY。
            st.execute("CREATE TABLE T (ID BIGINT, LEGACY VARCHAR(20))");
        }
        structure = new StructureSyncService(new com.qqmu.jync.service.converter.SqlBodyConverter());

        Project project = new Project();
        project.setId(11L);
        project.setName("P");
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        target.close();
    }

    @Test
    void anExistingTableGainsTheSourceColumnsItLacks() throws SQLException {
        TableMeta table = sourceTable(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR),
                col("AMOUNT", Types.DECIMAL));

        StructureSyncService.ApplyOutcome outcome = structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "T", "table create", table), ctx);

        assertThat(outcome.applied).isTrue();
        assertThat(outcome.detail).contains("NAME", "AMOUNT");
        assertThat(targetColumns()).contains("ID", "NAME", "AMOUNT");
        // 只加不删：源端没有的列不许动 —— 那可能是人家的业务列。
        assertThat(targetColumns()).contains("LEGACY");
        // 补完之后数据侧的形状预检必须放行，否则这一轮的补列等于没补。
        assertThat(TargetTableVerifier.verify(target, null, "T", table)).isNull();
    }

    @Test
    void addingIsIdempotentAcrossCycles() throws SQLException {
        TableMeta table = sourceTable(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR));

        structure.apply(target, ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "T", "first",
                table), ctx);
        StructureSyncService.ApplyOutcome second = structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "T", "second", table), ctx);

        // 第二轮没有可补的列：明细里不该再冒出「加了列」，否则变更日志会一直骗人。
        assertThat(second.applied).isTrue();
        assertThat(second.detail).doesNotContain("added");
        assertThat(targetColumns()).containsExactly("ID", "LEGACY", "NAME");
    }

    @Test
    void aWronglyTypedColumnIsLeftAlone() throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("ALTER TABLE T ADD COLUMN NAME INT");
        }
        TableMeta table = sourceTable(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR));

        StructureSyncService.ApplyOutcome outcome = structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "T", "table create", table), ctx);

        // 列在，只是类型不对：补列解决不了，必须留给形状预检去报，不能被这里悄悄改掉类型。
        assertThat(outcome.applied).isTrue();
        assertThat(columnType("NAME")).isEqualTo("INTEGER");
        assertThat(TargetTableVerifier.verify(target, null, "T", table))
                .contains("incompatible type");
    }

    @Test
    void aColumnThatCannotBeAddedFailsTheApplyInsteadOfClaimingSuccess() throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("INSERT INTO T (ID) VALUES (1)");
        }
        ColumnMeta mandatory = col("CODE", Types.VARCHAR);
        mandatory.setNullable(false);
        TableMeta table = sourceTable(col("ID", Types.BIGINT), mandatory);

        StructureSyncService.ApplyOutcome outcome = structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "T", "table create", table), ctx);

        // 表里已经有行，加一个非空无默认值的列是数据库自己拒绝的。这种失败必须如实上报：
        // 报成 ok 就等于告诉检测器"结构已同步"，那张表的快照会被写上，问题从此不可见。
        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("CODE");
    }

    /**
     * 结构同步关掉时，唯一允许对目标库做的表级操作是「建一张不存在的表」——数据侧在目标
     * 表被手工删掉时仍会发这一条 CREATE 兜底。表本来就在的时候，CREATE 只会返回
     * "already exists"，绝不能顺势补列、改注释、补身份序列：那个开关的语义就是「Jync 不
     * 改目标的表结构」。数据侧一旦读不出目标表清单（降级成逐表 CREATE），这条路径就必然会
     * 撞上已存在的表，所以这里用真实的已存在表把它钉住。
     */
    @Test
    void withStructureSyncOffAnExistingTableIsNeverAltered() throws SQLException {
        TableMeta table = sourceTable(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR));
        table.setRemarks("source comment");
        SyncConfig structureOff = new SyncConfig();
        structureOff.setSyncStructure(false);
        SyncContext offCtx = SyncContext.builder()
                .project(ctx.getProject())
                .config(structureOff)
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();

        StructureSyncService.ApplyOutcome outcome = structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "T", "auto create", table),
                offCtx);

        assertThat(outcome.applied).isTrue();
        assertThat(outcome.detail).doesNotContain("added");
        assertThat(targetColumns()).containsExactly("ID", "LEGACY");
    }

    private List<String> targetColumns() throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = target.getMetaData().getColumns(null, null, "T", "%")) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private String columnType(String column) throws SQLException {
        try (ResultSet rs = target.getMetaData().getColumns(null, null, "T", column)) {
            return rs.next() ? rs.getString("TYPE_NAME") : null;
        }
    }

    private static TableMeta sourceTable(ColumnMeta... columns) {
        TableMeta table = new TableMeta();
        table.setName("T");
        table.setColumns(List.of(columns));
        return table;
    }

    private static ColumnMeta col(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }
}
