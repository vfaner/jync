package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.service.cdc.CdcPositionStore;

@DataJpaTest
@Import(CdcPositionStore.class)
class CdcPositionRepositoryTest {

    @Autowired
    private CdcPositionRepository repository;

    @Autowired
    private CdcPositionStore store;

    @Test
    void recordBaselineStartCreatesRowWithBaselinePending() {
        CdcPosition saved = store.recordBaselineStart(7L, "mysql-bin.000003", 12345L, null, 612007L);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isBaselineDone()).isFalse();
        assertThat(saved.getServerId()).isEqualTo(612007L);

        Optional<CdcPosition> found = store.find(7L);
        assertThat(found).isPresent();
        assertThat(found.get().getBinlogFile()).isEqualTo("mysql-bin.000003");
        assertThat(found.get().getBinlogPos()).isEqualTo(12345L);
        assertThat(found.get().getGtidSet()).isNull();
    }

    @Test
    void recordBaselineStartIsIdempotentUpsertPerProject() {
        store.recordBaselineStart(9L, "mysql-bin.000001", 4L, null, 612009L);
        CdcPosition second = store.recordBaselineStart(9L, "mysql-bin.000002", 88L, "uuid:1-5", 612009L);
        assertThat(repository.findAll().stream().filter(p -> p.getProjectId().equals(9L)).count())
                .isEqualTo(1);
        assertThat(second.getBinlogFile()).isEqualTo("mysql-bin.000002");
        assertThat(second.getGtidSet()).isEqualTo("uuid:1-5");
        assertThat(second.isBaselineDone()).isFalse();
    }

    @Test
    void markBaselineDoneAndAdvancePersist() {
        store.recordBaselineStart(11L, "mysql-bin.000001", 4L, "uuid:1-2", 612011L);
        store.markBaselineDone(11L);
        store.advance(11L, "mysql-bin.000001", 900L, "uuid:1-9");

        CdcPosition pos = store.find(11L).orElseThrow();
        assertThat(pos.isBaselineDone()).isTrue();
        assertThat(pos.getBinlogPos()).isEqualTo(900L);
        assertThat(pos.getGtidSet()).isEqualTo("uuid:1-9");
    }

    @Test
    void advanceWithoutRowIsIgnored() {
        store.advance(404L, "mysql-bin.000009", 1L, null); // must not throw
        assertThat(store.find(404L)).isEmpty();
    }

    @Test
    void deleteRemovesRow() {
        store.recordBaselineStart(13L, "mysql-bin.000001", 4L, null, 612013L);
        store.delete(13L);
        assertThat(store.find(13L)).isEmpty();
    }

    // —— v1.6.0 LogMiner SCN variants ——

    @Test
    void recordBaselineStartScnCreatesRowWithoutBinlogColumns() {
        CdcPosition saved = store.recordBaselineStartScn(21L, 2_100_000L);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isBaselineDone()).isFalse();
        assertThat(saved.getScn()).isEqualTo(2_100_000L);
        // The two strategies carry their own columns — binlog fields stay untouched.
        assertThat(saved.getBinlogFile()).isNull();
        assertThat(saved.getBinlogPos()).isNull();
        assertThat(saved.getGtidSet()).isNull();
        assertThat(saved.getServerId()).isNull();
    }

    @Test
    void advanceScnUpdatesRowAndWarnsWhenMissing() {
        store.recordBaselineStartScn(7L, 1_234_567L);
        store.markBaselineDone(7L);
        store.advanceScn(7L, 1_234_999L);

        CdcPosition pos = store.find(7L).orElseThrow();
        assertThat(pos.getScn()).isEqualTo(1_234_999L);
        assertThat(pos.isBaselineDone()).isTrue();
        // Advance on a deleted row logs WARN but never throws (same contract as advance()).
        store.delete(7L);
        store.advanceScn(7L, 1_235_000L);
    }

    @Test
    void binlogAndScnColumnsCoexistWithoutPollution() {
        // A project recorded via binlog must not leak into the SCN column, and vice versa.
        store.recordBaselineStart(31L, "mysql-bin.000001", 100L, null, 612031L);
        assertThat(store.find(31L).orElseThrow().getScn()).isNull();

        store.recordBaselineStartScn(32L, 5_000_000L);
        CdcPosition scnPos = store.find(32L).orElseThrow();
        assertThat(scnPos.getBinlogFile()).isNull();
        assertThat(scnPos.getServerId()).isNull();
    }
}
