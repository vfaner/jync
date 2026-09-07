package com.synctool.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.synctool.model.AppUser;
import com.synctool.model.UserRole;
import com.synctool.repository.AppUserRepository;

/**
 * Exercises user creation, seeding, password validation, and the change flow.
 *
 * <p>Runs against a real H2 database so that a malformed query or constraint is caught here
 * rather than shipped.
 */
@DataJpaTest
class AppUserServiceTest {

    @Autowired
    private AppUserRepository repository;

    private AppUserService service;

    @BeforeEach
    void setUp() {
        service = new AppUserService(repository, new BCryptPasswordEncoder());
    }

    // ── Seeding ────────────────────────────────────────────────────────────────

    @Test
    void seedsOnEmptyTable() {
        service.seedIfEmpty();
        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findByUsername("admin")).isPresent();
        assertThat(repository.findByUsername("view")).isPresent();
    }

    @Test
    void doesNotSeedOnRestart() {
        // First call seeds.
        service.seedIfEmpty();
        repository.findByUsername("admin").ifPresent(u -> {
            String originalHash = u.getPasswordHash();
            // Second call must not alter the hash (i.e. must not re-encode the default password).
            service.seedIfEmpty();
            assertThat(repository.findByUsername("admin").get().getPasswordHash())
                    .isEqualTo(originalHash);
        });
    }

    @Test
    void doesNotSeedWhenTableHasContent() {
        service.seedIfEmpty();
        // Manually change the admin password so the default-password check is meaningful.
        AppUser admin = repository.findByUsername("admin").orElseThrow();
        admin.setPasswordHash(new BCryptPasswordEncoder().encode("changed"));
        repository.save(admin);

        // Re-seed must not reset the changed password.
        service.seedIfEmpty();
        AppUser reloaded = repository.findByUsername("admin").orElseThrow();
        assertThat(reloaded.getPasswordHash()).doesNotContain("123456");
    }

    // ── Password is stored as a BCrypt hash, not plaintext ─────────────────────

    @Test
    void passwordIsNotStoredInPlaintext() {
        service.seedIfEmpty();
        AppUser admin = repository.findByUsername("admin").orElseThrow();
        assertThat(admin.getPasswordHash())
                .startsWith("$2a$")   // BCrypt prefix
                .doesNotContain("123456");
    }

    // ── loadUserByUsername ─────────────────────────────────────────────────────

    @Test
    void loadsUser() {
        service.seedIfEmpty();
        SyncUserDetails details = service.loadUserByUsername("admin");
        assertThat(details.getUsername()).isEqualTo("admin");
        assertThat(details.isAdmin()).isTrue();
        assertThat(details.isUsingDefaultPassword()).isTrue();
    }

    @Test
    void loadsViewer() {
        service.seedIfEmpty();
        SyncUserDetails details = service.loadUserByUsername("view");
        assertThat(details.getUsername()).isEqualTo("view");
        assertThat(details.isAdmin()).isFalse();
        assertThat(details.isUsingDefaultPassword()).isTrue();
    }

    @Test
    void throwsOnUnknownUser() {
        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void defaultPasswordFlagIsFalseAfterChange() {
        service.seedIfEmpty();
        service.changeOwnPassword("admin", "123456", "newPw1", "newPw1");
        SyncUserDetails details = service.loadUserByUsername("admin");
        assertThat(details.isUsingDefaultPassword()).isFalse();
    }

    // ── changeOwnPassword ──────────────────────────────────────────────────────

    @Test
    void wrongCurrentPasswordRejected() {
        service.seedIfEmpty();
        assertThatThrownBy(() -> service.changeOwnPassword("admin", "wrong", "abc123", "abc123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("account.error.wrongCurrent");
    }

    @Test
    void tooShortRejected() {
        service.seedIfEmpty();
        assertThatThrownBy(() -> service.changeOwnPassword("admin", "123456", "ab1", "ab1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("account.error.tooShort");
    }

    @Test
    void mismatchRejected() {
        service.seedIfEmpty();
        assertThatThrownBy(() -> service.changeOwnPassword("admin", "123456", "abc123", "abc124"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("account.error.mismatch");
    }

    @Test
    void sameAsCurrentRejected() {
        service.seedIfEmpty();
        assertThatThrownBy(() -> service.changeOwnPassword("admin", "123456", "123456", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("account.error.sameAsCurrent");
    }

    @Test
    void viewUserCanAlsoChangePassword() {
        service.seedIfEmpty();
        service.changeOwnPassword("view", "123456", "viewNewPw1", "viewNewPw1");
        SyncUserDetails details = service.loadUserByUsername("view");
        assertThat(details.isUsingDefaultPassword()).isFalse();
    }

    @Test
    void changeAndLoginWithNewPassword() {
        service.seedIfEmpty();
        service.changeOwnPassword("admin", "123456", "newPw1", "newPw1");
        SyncUserDetails details = service.loadUserByUsername("admin");
        assertThat(new BCryptPasswordEncoder().matches("newPw1", details.getPassword())).isTrue();
    }

    @Test
    void throwsOnNonexistentUser() {
        assertThatThrownBy(() -> service.changeOwnPassword("ghost", "x", "y", "y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("account.error.notFound");
    }
}