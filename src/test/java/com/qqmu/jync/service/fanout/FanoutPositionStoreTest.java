package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.model.FanoutPosition;
import com.qqmu.jync.repository.FanoutPositionRepository;

class FanoutPositionStoreTest {

    private FanoutPositionRepository repository;
    private FanoutPositionStore store;

    @BeforeEach
    void setUp() {
        repository = mock(FanoutPositionRepository.class);
        store = new FanoutPositionStore(repository);
    }

    @Test
    void theFirstAdvanceCreatesTheTargetsRow() {
        when(repository.findByProjectIdAndTargetDbId(1L, 9L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.advance(1L, 9L, "binlog.000002", 120L, "uuid:1-5", null, null);

        ArgumentCaptor<FanoutPosition> saved = ArgumentCaptor.forClass(FanoutPosition.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProjectId()).isEqualTo(1L);
        assertThat(saved.getValue().getTargetDbId()).isEqualTo(9L);
        assertThat(saved.getValue().getBinlogFile()).isEqualTo("binlog.000002");
        assertThat(saved.getValue().getBinlogPos()).isEqualTo(120L);
        assertThat(saved.getValue().getGtidSet()).isEqualTo("uuid:1-5");
    }

    @Test
    void laterAdvancesUpdateTheExistingRow() {
        FanoutPosition existing = new FanoutPosition();
        existing.setProjectId(1L);
        existing.setTargetDbId(9L);
        existing.setBinlogFile("binlog.000001");
        existing.setBinlogPos(4L);
        when(repository.findByProjectIdAndTargetDbId(1L, 9L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.advance(1L, 9L, "binlog.000002", 88L, null, "0/16B6C50", 123L);

        assertThat(existing.getBinlogFile()).isEqualTo("binlog.000002");
        assertThat(existing.getBinlogPos()).isEqualTo(88L);
        assertThat(existing.getLsn()).isEqualTo("0/16B6C50");
        assertThat(existing.getScn()).isEqualTo(123L);
    }

    @Test
    void deletingOneTargetLeavesTheOtherTargetsRowsAlone() {
        store.delete(1L, 9L);

        verify(repository).deleteByProjectIdAndTargetDbId(1L, 9L);
    }

    @Test
    void deletingTheProjectDropsEveryTargetsRow() {
        store.deleteByProjectId(1L);

        verify(repository).deleteByProjectId(1L);
    }
}
