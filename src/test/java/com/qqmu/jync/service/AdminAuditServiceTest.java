package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.AuditEvent;
import com.qqmu.jync.repository.AuditEventRepository;

/**
 * What lands in an audit row: attribution, and the sanitization the failed-login path
 * depends on (its actor string is typed into a form by a possibly hostile client).
 */
@ExtendWith(MockitoExtension.class)
class AdminAuditServiceTest {

    @Mock private AuditEventRepository repository;

    // Built in setUp because the mock must exist first.
    private AdminAuditService real;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        real = new AdminAuditService(repository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AuditEvent saved() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordsTheSignedInUserAsTheActor() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));

        real.record(AuditAction.PROJECT_SAVE, "Updated project 'p'");

        AuditEvent event = saved();
        assertThat(event.getActor()).isEqualTo("admin");
        assertThat(event.getAction()).isEqualTo(AuditAction.PROJECT_SAVE);
        assertThat(event.getDetail()).isEqualTo("Updated project 'p'");
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    void fallsBackToSystemOutsideARequestContext() {
        real.record(AuditAction.CHANGELOG_CLEAR, "pruned");

        assertThat(saved().getActor()).isEqualTo("system");
    }

    @Test
    void aHostileUsernameIsStrippedOfControlCharactersAndCapped() {
        String hostile = "ad\r\nmin\n" + "x".repeat(120);

        real.record(AuditAction.LOGIN_FAILED, "BadCredentials from 10.0.0.1", hostile);

        AuditEvent event = saved();
        // No CR/LF (log- and UI-injection) and never longer than the column.
        assertThat(event.getActor()).doesNotContain("\r", "\n");
        assertThat(event.getActor()).hasSize(AdminAuditService.MAX_ACTOR);
        assertThat(event.getActor()).startsWith("admin");
    }

    @Test
    void aBlankActorFallsBackRatherThanSavingAnEmptyString() {
        real.record(AuditAction.LOGIN_FAILED, "detail", "   ");

        assertThat(saved().getActor()).isEqualTo("system");
    }

    @Test
    void anOverlongDetailIsTruncatedToTheColumnLength() {
        real.record(AuditAction.DB_SAVE, "y".repeat(2000), "admin");

        assertThat(saved().getDetail()).hasSize(AdminAuditService.MAX_DETAIL);
    }

    @Test
    void theTrailIsReadNewestFirstById() {
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        real.page(0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        assertThat(captor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
        assertThat(captor.getValue()).isEqualTo(PageRequest.of(0, 20,
                Sort.by(Sort.Direction.DESC, "id")));
    }
}
