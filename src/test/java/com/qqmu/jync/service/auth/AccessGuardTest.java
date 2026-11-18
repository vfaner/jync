package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.qqmu.jync.model.UserRole;

class AccessGuardTest {

    private static final AccessGuard ALICE = AccessGuard.of("alice", UserRole.OPS);
    private static final AccessGuard ADMIN = AccessGuard.of("admin", UserRole.ADMIN);
    private static final AccessGuard VIEWER = AccessGuard.of("view", UserRole.VIEWER);

    @Test
    void anAdminCanEditAnyOwnerIncludingTheSystemOne() {
        assertThat(ADMIN.canEdit("alice")).isTrue();
        assertThat(ADMIN.canEdit(null)).isTrue();
    }

    @Test
    void anOpsCanEditOnlyItsOwnResources() {
        assertThat(ALICE.canEdit("alice")).isTrue();
        assertThat(ALICE.canEdit("bob")).isFalse();
        assertThat(ALICE.canEdit(null)).isFalse();
    }

    @Test
    void aViewerCanEditNothing() {
        assertThat(VIEWER.canEdit("view")).isFalse();
        assertThat(VIEWER.canWrite()).isFalse();
    }

    @Test
    void requireEditableThrowsTheLocalizedKey() {
        assertThatThrownBy(() -> ALICE.requireEditable("bob"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("error.accessDenied");
        assertThatThrownBy(() -> VIEWER.requireWritable())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("error.accessDenied");
    }

    @Test
    void systemActsAsAnAdminWithoutAUsername() {
        assertThat(AccessGuard.system().isAdmin()).isTrue();
        assertThat(AccessGuard.system().username()).isNull();
    }

    @Test
    void currentIsAViewerWhenNobodyIsSignedIn() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThat(AccessGuard.current().isAdmin()).isFalse();
        assertThat(AccessGuard.current().canWrite()).isFalse();
        assertThat(AccessGuard.current().username()).isNull();
    }
}
