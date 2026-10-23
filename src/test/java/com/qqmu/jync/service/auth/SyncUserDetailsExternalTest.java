package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;

/**
 * OA principals live for one session: no database id, no local password, and a flag the
 * change-password page and the user menu both rely on.
 */
class SyncUserDetailsExternalTest {

    @Test
    void anExternalPrincipalHasNoLocalCredentials() {
        SyncUserDetails principal = SyncUserDetails.forExternalUser("alice", UserRole.VIEWER);

        assertThat(principal.isExternal()).isTrue();
        assertThat(principal.getId()).isNull();
        assertThat(principal.getUsername()).isEqualTo("alice");
        assertThat(principal.getPassword()).isEmpty();
        assertThat(principal.isUsingDefaultPassword()).isFalse();
        assertThat(principal.getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_VIEWER");
    }

    @Test
    void anExternalAdminCarriesTheAdminAuthority() {
        SyncUserDetails principal = SyncUserDetails.forExternalUser("root", UserRole.ADMIN);

        assertThat(principal.isAdmin()).isTrue();
        assertThat(principal.getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void aLocalPrincipalIsNotExternal() {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setUsername("local");
        user.setPasswordHash("hash");
        user.setRole(UserRole.ADMIN);

        SyncUserDetails principal = new SyncUserDetails(user, true);

        assertThat(principal.isExternal()).isFalse();
        assertThat(principal.getId()).isEqualTo(7L);
        assertThat(principal.getPassword()).isEqualTo("hash");
    }
}
