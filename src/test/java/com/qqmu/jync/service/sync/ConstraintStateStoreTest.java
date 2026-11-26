package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.model.ConstraintState;
import com.qqmu.jync.repository.ConstraintStateRepository;

class ConstraintStateStoreTest {

    @Test
    void theDisabledTableListSurvivesARestart() {
        ConstraintStateRepository repository = mock(ConstraintStateRepository.class);
        when(repository.findById(7L)).thenReturn(Optional.empty());
        ConstraintStateStore store = new ConstraintStateStore(repository);

        store.begin(7L, 3L, "dbo", List.of("ORDERS", "ITEMS"));

        ArgumentCaptor<ConstraintState> saved = ArgumentCaptor.forClass(ConstraintState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProjectId()).isEqualTo(7L);
        assertThat(saved.getValue().getTargetDbId()).isEqualTo(3L);
        assertThat(saved.getValue().getSchemaName()).isEqualTo("dbo");
        assertThat(saved.getValue().getTables().lines())
                .containsExactly("ORDERS", "ITEMS");
        assertThat(saved.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void clearingRemovesTheMarker() {
        ConstraintStateRepository repository = mock(ConstraintStateRepository.class);
        ConstraintStateStore store = new ConstraintStateStore(repository);

        store.clear(7L);

        verify(repository).deleteById(7L);
    }

    @Test
    void anEmptyTableListNeedsNoMarker() {
        ConstraintStateRepository repository = mock(ConstraintStateRepository.class);
        ConstraintStateStore store = new ConstraintStateStore(repository);

        store.begin(7L, 3L, "dbo", List.of());

        verify(repository, org.mockito.Mockito.never()).save(any());
    }
}
