package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.repository.CdcPositionRepository;

/**
 * The 2026-11-20 incident failed at <em>commit</em> time, not at the mocked level: the
 * optimistic-lock exception is thrown by Spring's transaction interceptor when
 * {@code repository.save} returns, which a mock cannot reproduce. This test runs the store
 * against a real H2 store, without a surrounding test transaction (the CDC consumer thread
 * has none either), and lets a competing writer commit first.
 */
@DataJpaTest
@Import(CdcPositionStore.class)
class CdcPositionStoreRealConflictTest {

    @Autowired
    private CdcPositionRepository repository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anAdvanceLosesTheRaceAgainstACompetingCommitWithoutFailingTheStream() {
        new CdcPositionStore(repository, 0L)
                .recordBaselineStart(31L, "mysql-bin.000001", 4L, null, 612031L);

        // Both writers read the row at version 0; the other one commits first.
        CdcPosition stale = repository.findByProjectId(31L).orElseThrow();
        CdcPosition competing = repository.findByProjectId(31L).orElseThrow();
        competing.setBinlogPos(50L);
        repository.saveAndFlush(competing);

        // The store's read-modify-write now runs on the outdated snapshot, so its save
        // collides with the committed version+1 and fails at commit time. Every other call
        // is forwarded to the real repository proxy, so save() runs a real transaction.
        CdcPositionRepository staleReader = mock(CdcPositionRepository.class, invocation -> {
            if (invocation.getMethod().getName().equals("findByProjectId")) {
                return Optional.of(stale);
            }
            try {
                return invocation.getMethod().invoke(repository, invocation.getArguments());
            } catch (InvocationTargetException e) {
                // Mirror how the real call behaves: the optimistic-lock failure must reach
                // the store as itself, not wrapped in a reflective exception.
                throw e.getCause();
            }
        });
        CdcPositionStore store = new CdcPositionStore(staleReader, 0L);

        assertThatCode(() -> store.advance(31L, "mysql-bin.000001", 900L, null))
                .doesNotThrowAnyException();

        // The competing writer's position survived — the loser did not clobber it.
        assertThat(repository.findByProjectId(31L).orElseThrow().getBinlogPos())
                .isEqualTo(50L);
    }
}
