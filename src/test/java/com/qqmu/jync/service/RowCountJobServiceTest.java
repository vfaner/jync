package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.TableRowCount;

class RowCountJobServiceTest {

    @Test
    void runsAnExactCountOutOfBandAndExposesTheResult() throws Exception {
        TablePreviewService preview = mock(TablePreviewService.class);
        TableRowCount counts = new TableRowCount("orders",
                new TableRowCount.SideCount(TableRowCount.Status.OK, 3_000_000L, 10L),
                new TableRowCount.SideCount(TableRowCount.Status.OK, 2_999_000L, 11L),
                21L);
        when(preview.rowCount(1L, "orders", true, 300)).thenReturn(counts);
        RowCountJobService service = new RowCountJobService(preview, new SyncProperties());

        String id = service.submit(1L, "orders");
        Optional<RowCountJobService.JobView> done = waitFor(service, id);

        assertThat(done).isPresent();
        assertThat(done.get().status()).isEqualTo(RowCountJobService.Status.DONE);
        assertThat(done.get().rowCount().source().count()).isEqualTo(3_000_000L);
    }

    @Test
    void aJobIsNotVisibleFromAnotherProject() {
        TablePreviewService preview = mock(TablePreviewService.class);
        RowCountJobService service = new RowCountJobService(preview, new SyncProperties());

        String id = service.submit(1L, "orders");

        assertThat(service.find(2L, id)).isEmpty();
    }

    private static Optional<RowCountJobService.JobView> waitFor(RowCountJobService service,
                                                                 String id) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<RowCountJobService.JobView> job = service.find(1L, id);
            if (job.isPresent() && job.get().status() != RowCountJobService.Status.RUNNING) {
                return job;
            }
            Thread.sleep(20);
        }
        return service.find(1L, id);
    }
}
