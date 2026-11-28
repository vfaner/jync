package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.qqmu.jync.dto.ProgressStatusRow;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;

/**
 * The status projection must be evaluated by the database, not only by mocks: the table
 * panel polls it every few seconds, and the cursor-presence CASE is what distinguishes a
 * completed table from one whose cursor was cleared for a reload.
 */
@DataJpaTest
class SyncProgressStatusProjectionTest {

    @Autowired private SyncProgressRepository repository;

    @Test
    void theProjectionReportsWhetherTheCursorIsPresentWithoutLoadingTheLob() {
        SyncProgress present = progress("ORDERS", "41");
        SyncProgress cleared = progress("CUSTOMERS", null);
        repository.saveAll(List.of(present, cleared));

        List<ProgressStatusRow> rows = repository.findStatusRowsByProjectId(
                7L, ObjectType.TABLE);

        assertThat(rows).extracting(ProgressStatusRow::objectName)
                .containsExactlyInAnyOrder("ORDERS", "CUSTOMERS");
        assertThat(rows).filteredOn(row -> row.objectName().equals("ORDERS"))
                .singleElement()
                .extracting(ProgressStatusRow::cursorPresent)
                .isEqualTo(true);
        assertThat(rows).filteredOn(row -> row.objectName().equals("CUSTOMERS"))
                .singleElement()
                .extracting(ProgressStatusRow::cursorPresent)
                .isEqualTo(false);
    }

    private static SyncProgress progress(String table, String cursor) {
        SyncProgress progress = new SyncProgress();
        progress.setProjectId(7L);
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName(table);
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(cursor);
        progress.setCursorStrategy("TIMESTAMP");
        progress.setCursorColumn("UPDATED_AT");
        progress.setRowsSyncedTotal(10L);
        return progress;
    }
}
