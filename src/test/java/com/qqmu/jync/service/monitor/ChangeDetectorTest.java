package com.qqmu.jync.service.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;

/**
 * An object whose body could not be read from the source still EXISTS at the source. Treating
 * it as unseen used to let the drop detection conclude it vanished, and with allowDrop on the
 * target's copy was destroyed — a permissions hiccup at the source deleting real objects at
 * the target.
 */
class ChangeDetectorTest {

    private MetadataSnapshotService snapshotService;
    private ChangeDetector detector;

    @BeforeEach
    void setUp() {
        snapshotService = mock(MetadataSnapshotService.class);
        detector = new ChangeDetector(snapshotService);
    }

    private SyncConfig config() {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setAllowDrop(true);
        return config;
    }

    @Test
    void aViewWithAnUnreadableBodyIsNotReportedAsDropped() {
        ViewMeta stored = new ViewMeta();
        stored.setName("V_ORDERS");
        stored.setDefinition("SELECT 1");
        when(snapshotService.loadAll(1L, ObjectType.VIEW, ViewMeta.class))
                .thenReturn(Map.of("V_ORDERS", stored));

        ViewMeta live = new ViewMeta();
        live.setName("V_ORDERS");
        live.setDefinition(null); // a transient failure to read the body
        DatabaseMeta current = new DatabaseMeta();
        current.setViews(List.of(live));

        SyncConfig config = config();
        config.setSyncViews(true);
        List<ChangeEvent> events = detector.detect(1L, current, config);

        assertThat(events).isEmpty();
    }

    @Test
    void aRoutineWithAnUnreadableBodyIsNotReportedAsDropped() {
        ProcedureMeta stored = new ProcedureMeta();
        stored.setName("P_CALC");
        stored.setDefinition("BEGIN END");
        when(snapshotService.loadAll(1L, ObjectType.PROCEDURE, ProcedureMeta.class))
                .thenReturn(Map.of("P_CALC", stored));

        ProcedureMeta live = new ProcedureMeta();
        live.setName("P_CALC");
        live.setDefinition(""); // e.g. the account lacks rights to read routine source
        DatabaseMeta current = new DatabaseMeta();
        current.setProcedures(List.of(live));

        SyncConfig config = config();
        config.setSyncProcedures(true);
        List<ChangeEvent> events = detector.detect(1L, current, config);

        assertThat(events).isEmpty();
    }

    @Test
    void aViewThatReallyDisappearedIsStillDropped() {
        // The guard must not disable legitimate drop detection.
        ViewMeta stored = new ViewMeta();
        stored.setName("V_GONE");
        stored.setDefinition("SELECT 1");
        when(snapshotService.loadAll(1L, ObjectType.VIEW, ViewMeta.class))
                .thenReturn(Map.of("V_GONE", stored));

        DatabaseMeta current = new DatabaseMeta(); // the view is absent from the source read

        SyncConfig config = config();
        config.setSyncViews(true);
        List<ChangeEvent> events = detector.detect(1L, current, config);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getChangeType()).isEqualTo(ChangeType.DROP);
        assertThat(events.get(0).getObjectName()).isEqualTo("V_GONE");
    }

    @Test
    void aViewWithAReadableBodyStillDiffsNormally() {
        ViewMeta stored = new ViewMeta();
        stored.setName("V_ORDERS");
        stored.setDefinition("SELECT 1");
        when(snapshotService.loadAll(1L, ObjectType.VIEW, ViewMeta.class))
                .thenReturn(Map.of("V_ORDERS", stored));

        ViewMeta live = new ViewMeta();
        live.setName("V_ORDERS");
        live.setDefinition("SELECT 2"); // changed body
        DatabaseMeta current = new DatabaseMeta();
        current.setViews(List.of(live));

        SyncConfig config = config();
        config.setSyncViews(true);
        List<ChangeEvent> events = detector.detect(1L, current, config);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getChangeType()).isEqualTo(ChangeType.ALTER);
    }
}
