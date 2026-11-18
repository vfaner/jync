package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.AppUserRepository;
import com.qqmu.jync.repository.SystemSettingRepository;
import com.qqmu.jync.service.settings.SystemSettingKey;
import com.qqmu.jync.service.settings.SystemSettingService;

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
    @Autowired
    private SystemSettingRepository systemSettings;

    private AppUserService service;

    @BeforeEach
    void setUp() {
        service = new AppUserService(repository, new BCryptPasswordEncoder(),
                new SystemSettingService(systemSettings));
    }

    // ── Seeding ────────────────────────────────────────────────────────────────

    @Test
    void seedsOnEmptyTable() {
        service.seedIfEmpty();
        assertThat(repository.count()).isEqualTo(3);
        assertThat(repository.findByUsername("admin")).get()
                .extracting(AppUser::getRole).isEqualTo(UserRole.ADMIN);
        assertThat(repository.findByUsername("ops")).get()
                .extracting(AppUser::getRole).isEqualTo(UserRole.OPS);
        assertThat(repository.findByUsername("view")).get()
                .extracting(AppUser::getRole).isEqualTo(UserRole.VIEWER);
        assertThat(systemSettings.findById(SystemSettingKey.LOCAL_OPS_SEEDED.key()))
                .isPresent();
    }

    @Test
    void upgradesAnExistingInstallationWithAnOpsAccount() {
        repository.save(user("admin", UserRole.ADMIN));
        repository.save(user("view", UserRole.VIEWER));

        service.seedIfEmpty();

        assertThat(repository.findByUsername("ops")).get()
                .extracting(AppUser::getRole).isEqualTo(UserRole.OPS);
        assertThat(systemSettings.findById(SystemSettingKey.LOCAL_OPS_SEEDED.key()))
                .isPresent();
    }

    @Test
    void aDeletedOpsAccountIsNotRecreatedAfterTheMarkerExists() {
        service.seedIfEmpty();
        repository.delete(repository.findByUsername("ops").orElseThrow());

        service.seedIfEmpty();

        assertThat(repository.findByUsername("ops")).isEmpty();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void anExistingOpsRoleOnlyGetsTheMarker() {
        repository.save(user("admin", UserRole.ADMIN));
        repository.save(user("view", UserRole.VIEWER));
        repository.save(user("sso-ops", UserRole.OPS));
        long before = repository.count();

        service.seedIfEmpty();

        assertThat(repository.count()).isEqualTo(before);
        assertThat(repository.findByUsername("ops")).isEmpty();
        assertThat(systemSettings.findById(SystemSettingKey.LOCAL_OPS_SEEDED.key()))
                .isPresent();
    }

    @Test
    void loadsOps() {
        service.seedIfEmpty();
        SyncUserDetails details = service.loadUserByUsername("ops");
        assertThat(details.isOps()).isTrue();
        assertThat(details.isAdmin()).isFalse();
        assertThat(details.isUsingDefaultPassword()).isTrue();
    }

    private static AppUser user(String username, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("$2a$10$not-a-real-hash");
        user.setRole(role);
        return user;
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
    void unknownUserStillRunsOnePasswordComparison() {
        // A missing user must cost one BCrypt verification, else reply timing enumerates users.
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
                org.mockito.Mockito.spy(new BCryptPasswordEncoder());
        AppUserService svc = new AppUserService(repository, encoder,
                new SystemSettingService(systemSettings));

        assertThatThrownBy(() -> svc.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);

        org.mockito.Mockito.verify(encoder)
                .matches(org.mockito.ArgumentMatchers.anyString(),
                         org.mockito.ArgumentMatchers.startsWith("$2"));
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

    /**
     * The forced-change gate exists because the seeded password is public knowledge; letting the
     * user set it back would turn the gate into a formality that ends by restoring the very
     * credential it was meant to remove.
     */
    @Test
    void refusesTheDefaultPasswordAsTheNewOne() {
        service.seedIfEmpty();
        service.changeOwnPassword("admin", "123456", "newPw1", "newPw1");

        assertThatThrownBy(() -> service.changeOwnPassword("admin", "newPw1", "123456", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("account.error.defaultPassword");
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