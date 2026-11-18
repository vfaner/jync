package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserRoleTest {

    @Test
    void everyRoleHasItsOwnAuthority() {
        assertThat(UserRole.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
        assertThat(UserRole.OPS.authority()).isEqualTo("ROLE_OPS");
        assertThat(UserRole.VIEWER.authority()).isEqualTo("ROLE_VIEWER");
    }
}
