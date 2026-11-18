package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;

/** The three-way mapping from an external username to a Jync role. */
class ExternalUserRolesTest {

    @Test
    void opsUsersGrantsOpsAndAdminWinsAHatTrick() {
        OaAuthConfig config = new OaAuthConfig();
        config.setAdminUsers("root, alice");
        config.setOpsUsers("bob, alice");

        assertThat(ExternalUserRoles.resolve(config, "root", false)).isEqualTo(UserRole.ADMIN);
        assertThat(ExternalUserRoles.resolve(config, "alice", false)).isEqualTo(UserRole.ADMIN);
        assertThat(ExternalUserRoles.resolve(config, "bob", false)).isEqualTo(UserRole.OPS);
        assertThat(ExternalUserRoles.resolve(config, "carol", false)).isEqualTo(UserRole.VIEWER);
        assertThat(ExternalUserRoles.resolve(config, "bob", true)).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void aMissingOpsListGrantsNothing() {
        OaAuthConfig config = new OaAuthConfig();
        config.setAdminUsers("root");

        assertThat(ExternalUserRoles.resolve(config, "bob", false)).isEqualTo(UserRole.VIEWER);
    }
}
