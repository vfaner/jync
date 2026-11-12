package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

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
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void rowsWrittenByTheRemovedMetadataDbTesterStillLoad() {
        // v2.0.x and v2.1.0 shipped a 系统配置 connection tester that appended SYSTEM_DB_TEST
        // rows. The page and endpoint are gone, but an append-only trail keeps those rows, and
        // @Enumerated(STRING) mapping is strict: dropping the constant would make this query
        // (and therefore the whole audit page) fail on every upgraded installation.
        jdbcTemplate.update("insert into audit_event (occurred_at, actor, action, detail)"
                        + " values (?, ?, ?, ?)",
                Timestamp.from(Instant.now()), "admin", "SYSTEM_DB_TEST",
                "{audit.detail.dbTest}|jdbc:mysql://***:3306/jync|MySQL 8.0|OK");

        Page<AuditEvent> page = repository.findAll(PageRequest.of(0, 10));

        assertThat(page.getContent()).singleElement()
                .extracting(AuditEvent::getAction).extracting(Enum::name)
                .isEqualTo("SYSTEM_DB_TEST");
    }
}
