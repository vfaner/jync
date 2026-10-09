package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.AuditEvent;

/**
 * The audit repository against a real H2 schema.
 *
 * <p>Two things worth proving: the marker-interface repository (deliberately not a
 * {@code CrudRepository}, so no delete method exists to call) really supports save/paging/
 * count, and the newest-first id ordering the page renders is what the query returns.
 */
@DataJpaTest
class AuditEventRepositoryDataTest {

    @Autowired
    private AuditEventRepository repository;

    private void record(String actor, AuditAction action, String detail) {
        repository.save(AuditEvent.of(actor, action, detail));
    }

    @Test
    void savesAndCountsRows() {
        record("admin", AuditAction.LOGIN, "Signed in from 127.0.0.1");
        record("mallory", AuditAction.LOGIN_FAILED, "BadCredentials from 10.0.0.9");

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void pagesNewestFirstById() {
        record("admin", AuditAction.LOGIN, "first");
        record("admin", AuditAction.DB_SAVE, "second");
        record("admin", AuditAction.CHANGELOG_CLEAR, "third");

        Page<AuditEvent> page = repository.findAll(
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(AuditEvent::getDetail)
                .containsExactly("third", "second");
        assertThat(page.hasNext()).isTrue();
    }
}
