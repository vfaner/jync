package com.qqmu.jync.service.auth;


import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.AppUserRepository;
import com.qqmu.jync.service.settings.SystemSettingKey;
import com.qqmu.jync.service.settings.SystemSettingService;

import lombok.extern.slf4j.Slf4j;

/**
 * Loads users for authentication, seeds the initial three accounts, and changes passwords.
 *
 * @see SyncUserDetails
 */
@Service
@Slf4j
public class AppUserService implements UserDetailsService {

    /**
     * Handed to all seeded accounts, and the value the layout warns about while it is still in
     * use. Public because the warning check and the tests both need the same constant — a second
     * copy of this string somewhere else is a bug waiting to happen.
     */
    public static final String DEFAULT_PASSWORD = "123456";

    public static final String ADMIN_USERNAME = "admin";
    public static final String OPS_USERNAME = "ops";
    public static final String VIEWER_USERNAME = "view";

    /**
     * A precomputed BCrypt hash compared against whenever the username does not exist, so a
     * missing-user request spends the same time as a real one. Without it, the instant
     * {@code UsernameNotFoundException} reply is a username-enumeration oracle.
     */
    private static final String TIMING_DUMMY_HASH =
            new BCryptPasswordEncoder().encode("timing-equalization-dummy");

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SystemSettingService settings;

    public AppUserService(AppUserRepository repository, PasswordEncoder passwordEncoder,
                          SystemSettingService settings) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
    }

    /**
     * Creates the three starting accounts, but only when there are no users at all.
     *
     * <p>The emptiness check is the whole safety property. Seeding "if this username is missing"
     * would recreate an account the operator deliberately deleted, and seeding unconditionally
     * would reset a changed password on every restart — a silent security regression that nobody
     * would notice until someone tried the default and got in.
     *
     * <p>Installations upgraded from before the operator role existed have no local OPS account,
     * so they get one added once. The {@code seed.local-ops} marker makes that a one-time
     * migration: deleting the account afterwards is deliberate and is never undone.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedIfEmpty() {
        if (repository.count() == 0) {
            create(ADMIN_USERNAME, DEFAULT_PASSWORD, UserRole.ADMIN);
            create(OPS_USERNAME, DEFAULT_PASSWORD, UserRole.OPS);
            create(VIEWER_USERNAME, DEFAULT_PASSWORD, UserRole.VIEWER);
            settings.putBoolean(SystemSettingKey.LOCAL_OPS_SEEDED, true);
            log.warn("Seeded users '{}' (ADMIN), '{}' (OPS) and '{}' (VIEWER) with the default "
                    + "password. Change all three before exposing this instance.",
                    ADMIN_USERNAME, OPS_USERNAME, VIEWER_USERNAME);
            return;
        }
        if (settings.find(SystemSettingKey.LOCAL_OPS_SEEDED).isPresent()) {
            // Already handled once: an operator who deleted the account meant it.
            return;
        }
        if (repository.countByRole(UserRole.OPS) == 0) {
            if (repository.findByUsername(OPS_USERNAME).isPresent()) {
                // Some installations already have a local account called "ops" that predates the
                // operator role. The username is unique, so inserting a second one throws from
                // this listener and takes the process down on every restart. Leave their account
                // and its role alone. The marker is deliberately not written: the migration has
                // not happened, so it retries on the next boot and succeeds once that account
                // has been renamed.
                log.warn("Upgraded installation: a local account named '{}' already exists but is "
                        + "not an operator, so no operator account was seeded. Rename that "
                        + "account in the metadata store and restart to get one.", OPS_USERNAME);
                return;
            }
            create(OPS_USERNAME, DEFAULT_PASSWORD, UserRole.OPS);
            log.warn("Upgraded installation: created local '{}' account with the default password. "
                    + "Change it before exposing this instance.", OPS_USERNAME);
        }
        settings.putBoolean(SystemSettingKey.LOCAL_OPS_SEEDED, true);
    }

    private void create(String username, String rawPassword, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        repository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public SyncUserDetails loadUserByUsername(String username) {
        AppUser user = repository.findByUsername(username).orElse(null);
        if (user == null) {
            // Burn one BCrypt verification so this branch costs as much as the real one below.
            passwordEncoder.matches(username, TIMING_DUMMY_HASH);
            throw new UsernameNotFoundException("No such user");
        }
        // Resolved here, at sign-in, and carried on the principal for the life of the session.
        boolean isDefault = passwordEncoder.matches(DEFAULT_PASSWORD, user.getPasswordHash());
        return new SyncUserDetails(user, isDefault);
    }

    /**
     * Replaces a user's own password after verifying the current one.
     *
     * <p>Verifying the current password matters even though the caller is already authenticated:
     * it is what stops someone who walked up to an unlocked browser from locking the real owner
     * out of their own account.
     *
     * @throws IllegalArgumentException with a message-bundle key, so the page can render it in
     *     the user's language rather than showing an English sentence from the server
     */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword,
                                  String confirmPassword) {
        AppUser user = repository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("account.error.notFound"));

        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("account.error.wrongCurrent");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("account.error.tooShort");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("account.error.mismatch");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("account.error.sameAsCurrent");
        }
        // The seeded password is public knowledge; picking it back would restore exactly the
        // state the forced-change gate exists to end.
        if (DEFAULT_PASSWORD.equals(newPassword)) {
            throw new IllegalArgumentException("account.error.defaultPassword");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        repository.save(user);
        log.info("Password changed for user '{}'", username);
    }
}
