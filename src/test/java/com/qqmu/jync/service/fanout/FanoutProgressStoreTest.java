package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.FanoutProgress;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.repository.FanoutProgressRepository;

class FanoutProgressStoreTest {

    private FanoutProgressRepository repository;
    private FanoutProgressStore store;

    @BeforeEach
    void setUp() {
        repository = mock(FanoutProgressRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        store = new FanoutProgressStore(repository);
    }

    @Test
    void progressIsScopedToTheTarget() {
        when(repository.findByProjectIdAndTargetDbIdAndObjectTypeAndObjectName(
                1L, 9L, ObjectType.TABLE, "orders")).thenReturn(Optional.empty());

        FanoutProgress created = store.loadOrCreate(1L, 9L, "orders");

        assertThat(created.getProjectId()).isEqualTo(1L);
        assertThat(created.getTargetDbId()).isEqualTo(9L);
        assertThat(created.getObjectName()).isEqualTo("orders");
        assertThat(created.getInitialLoadDone()).isFalse();
        // The same table under another target is a different row.
        assertThat(store.find(1L, 10L, "orders")).isEmpty();
    }
}
