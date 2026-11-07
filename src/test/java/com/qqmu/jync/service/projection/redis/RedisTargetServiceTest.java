package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.RedisTargetForm;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Credential handling, the delete guard, and the reachability probe. These are the rules that
 * a mistake would turn into either a leaked password or an orphaned project target.
 */
@ExtendWith(MockitoExtension.class)
class RedisTargetServiceTest {

    @Mock private RedisTargetRepository targets;
    @Mock private ProjectRepository projects;
    @Mock private CryptoUtil cryptoUtil;
    @Mock private AdminAuditService auditService;

    private RedisTargetService service;

    @BeforeEach
    void setUp() {
        service = new RedisTargetService(targets, projects, cryptoUtil, auditService);
    }

    @Test
    void passwordIsEncryptedAndNeverStoredInPlainText() {
        when(cryptoUtil.encrypt("s3cret")).thenReturn("enc:cipher");
        when(targets.save(any())).thenAnswer(i -> i.getArgument(0));

        service.save(form("cache-1", null), "s3cret");

        ArgumentCaptor<RedisTarget> saved = ArgumentCaptor.forClass(RedisTarget.class);
        verify(targets).save(saved.capture());
        assertThat(saved.getValue().getPasswordEnc()).isEqualTo("enc:cipher");
        assertThat(saved.getValue().getPasswordEnc()).doesNotContain("s3cret");
    }

    @Test
    void blankPasswordOnEditKeepsTheStoredOne() {
        RedisTarget existing = new RedisTarget();
        existing.setId(3L);
        existing.setName("cache-1");
        existing.setPasswordEnc("enc:stored");
        when(targets.findById(3L)).thenReturn(Optional.of(existing));
        when(targets.save(any())).thenAnswer(i -> i.getArgument(0));

        RedisTarget saved = service.save(form("cache-1", 3L), "");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getPasswordEnc()).isEqualTo("enc:stored");
        verify(cryptoUtil, never()).encrypt(any());
    }

    @Test
    void anEditReusesTheLoadedEntityRatherThanCreatingADuplicate() {
        RedisTarget existing = new RedisTarget();
        existing.setId(3L);
        existing.setName("cache-1");
        existing.setOptVersion(7L);
        when(targets.findById(3L)).thenReturn(Optional.of(existing));
        when(targets.save(any())).thenAnswer(i -> i.getArgument(0));

        RedisTarget saved = service.save(form("cache-1", 3L), null);

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getOptVersion()).isEqualTo(7L);
    }

    @Test
    void deleteIsRefusedWhileAProjectReferencesIt() {
        when(targets.findById(3L)).thenReturn(Optional.of(new RedisTarget()));
        when(projects.existsByTargetRedisId(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.redis.target.inUse");
        verify(targets, never()).deleteById(any());
    }

    @Test
    void editingIsRefusedWhileAReferencingProjectIsStillRunning() {
        // A running consumer keeps the connection it opened from the old settings. Letting the
        // row change under it means the next page view and the stream disagree about where the
        // cache is, and restarting the project is the only way to pick the new values up.
        // Database connections answer this the same way (error.connection.edit.running).
        RedisTarget existing = new RedisTarget();
        existing.setId(3L);
        existing.setName("cache-1");
        when(targets.findById(3L)).thenReturn(Optional.of(existing));
        com.qqmu.jync.model.Project running = new com.qqmu.jync.model.Project();
        running.setId(7L);
        running.setName("orders-cache");
        running.setEnabled(true);
        com.qqmu.jync.model.Project stopped = new com.qqmu.jync.model.Project();
        stopped.setId(8L);
        stopped.setName("users-cache");
        stopped.setEnabled(false);
        when(projects.findByTargetRedisId(3L)).thenReturn(java.util.List.of(running, stopped));

        assertThatThrownBy(() -> service.save(form("cache-1", 3L), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.redis.target.edit.running:orders-cache");
        verify(targets, never()).save(any());
    }

    @Test
    void verifyReportsUnreachableWithoutThrowing() {
        RedisTargetForm form = form("cache-1", null);
        form.setHost("127.0.0.1");
        form.setPort(1);
        form.setConnectTimeoutMs(300);

        VerifyResult result = service.verify(form, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.messageKey()).isEqualTo("redis.target.verify.failed");
        // The failure detail must never carry the credential, even when one was supplied.
        assertThat(result.detail()).doesNotContain("s3cret");
    }

    @Test
    void aNameIsRequired() {
        RedisTargetForm form = form("  ", null);

        assertThatThrownBy(() -> service.save(form, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.redis.target.name.required");
    }

    @Test
    void aDuplicateNameIsRefused() {
        RedisTarget other = new RedisTarget();
        other.setId(9L);
        when(targets.findByName("cache-1")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.save(form("cache-1", null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.redis.target.name.duplicate");
    }

    @Test
    void aClusterTargetMustUseDatabaseZero() {
        RedisTargetForm form = form("cache-1", null);
        form.setClusterMode(true);
        form.setDatabaseIndex(3);

        assertThatThrownBy(() -> service.save(form, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.redis.target.cluster.database");
    }

    @Test
    void aClearedDatabaseIndexFallsBackToZeroOnEdit() {
        // The number input can be emptied, and Spring binds an empty value as null. The column
        // is NOT NULL and @PreUpdate does not normalise it, so writing the raw null turns an
        // ordinary edit into a 500 instead of the documented default.
        RedisTarget existing = new RedisTarget();
        existing.setId(3L);
        existing.setName("cache-1");
        when(targets.findById(3L)).thenReturn(Optional.of(existing));
        when(targets.save(any())).thenAnswer(i -> i.getArgument(0));
        RedisTargetForm form = form("cache-1", 3L);
        form.setDatabaseIndex(null);

        RedisTarget saved = service.save(form, null);

        assertThat(saved.getDatabaseIndex()).isZero();
    }

    @Test
    void anOutOfRangePortIsRefused() {
        RedisTargetForm form = form("cache-1", null);
        form.setPort(70000);

        assertThatThrownBy(() -> service.save(form, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.redis.target.port.invalid");
    }

    private static RedisTargetForm form(String name, Long id) {
        RedisTargetForm form = new RedisTargetForm();
        form.setId(id);
        form.setName(name);
        form.setHost("127.0.0.1");
        form.setPort(6379);
        return form;
    }
}
