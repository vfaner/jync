package com.qqmu.jync.service.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;

/**
 * Comments are not part of any signature — deliberately, so a comment edit does not look
 * like a type change — which used to mean a comment edited at the source never produced a
 * change event and never reached an existing target table.
 */
class ChangeDetectorCommentsTest {

    private MetadataSnapshotService snapshotService;
    private ChangeDetector detector;

    @BeforeEach
    void setUp() {
        snapshotService = mock(MetadataSnapshotService.class);
        detector = new ChangeDetector(snapshotService);
    }

    @Test
    void aChangedTableCommentIsDetected() {
        givenSnapshot(table("旧表注释", "旧列注释"));
        DatabaseMeta current = current(table("新表注释", "旧列注释"));

        List<ChangeEvent> events = detector.detect(1L, current, config());

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getObjectType()).isEqualTo(ObjectType.TABLE);
            assertThat(event.getChangeType()).isEqualTo(ChangeType.ALTER);
        });
    }

    @Test
    void aChangedColumnCommentIsDetectedAsAColumnAlter() {
        givenSnapshot(table("表注释", "旧列注释"));
        DatabaseMeta current = current(table("表注释", "新列注释"));

        List<ChangeEvent> events = detector.detect(1L, current, config());

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getObjectType()).isEqualTo(ObjectType.COLUMN);
            assertThat(event.getChangeType()).isEqualTo(ChangeType.ALTER);
            assertThat(event.getDetailName()).isEqualTo("NAME");
        });
    }

    @Test
    void aRemovedCommentIsDetectedSoTheTargetDoesNotKeepIt() {
        givenSnapshot(table("表注释", "旧列注释"));
        DatabaseMeta current = current(table(null, null));

        List<ChangeEvent> events = detector.detect(1L, current, config());

        assertThat(events).anyMatch(e -> e.getObjectType() == ObjectType.TABLE
                && e.getChangeType() == ChangeType.ALTER);
        assertThat(events).anyMatch(e -> e.getObjectType() == ObjectType.COLUMN
                && e.getChangeType() == ChangeType.ALTER);
    }

    @Test
    void absentAndBlankCommentsAreTheSameThing() {
        // Some drivers report "" where others report null; that must not become an ALTER
        // on every cycle.
        givenSnapshot(table(null, null));
        DatabaseMeta current = current(table("", "  "));

        assertThat(detector.detect(1L, current, config())).isEmpty();
    }

    @Test
    void anUnchangedTableProducesNoEvents() {
        givenSnapshot(table("表注释", "列注释"));
        DatabaseMeta current = current(table("表注释", "列注释"));

        assertThat(detector.detect(1L, current, config())).isEmpty();
    }

    private void givenSnapshot(TableMeta previous) {
        when(snapshotService.loadAll(1L, ObjectType.TABLE, TableMeta.class))
                .thenReturn(Map.of("ORDERS", previous));
    }

    private static DatabaseMeta current(TableMeta table) {
        DatabaseMeta meta = new DatabaseMeta();
        meta.getTables().add(table);
        return meta;
    }

    private static SyncConfig config() {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(true);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setAllowDrop(false);
        return config;
    }

    private static TableMeta table(String tableComment, String columnComment) {
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        table.setRemarks(tableComment);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setTypeName("VARCHAR");
        name.setJdbcType(Types.VARCHAR);
        name.setSize(50);
        name.setNullable(true);
        name.setRemarks(columnComment);
        table.getColumns().add(name);
        return table;
    }
}
